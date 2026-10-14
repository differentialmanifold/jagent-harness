package io.github.differentialmanifold.jagentharness.store.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.runner.RunnerStore;
import io.github.differentialmanifold.jagentharness.protocol.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class JdbcRunnerStore implements RunnerStore {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final String app;
    private final TransactionTemplate tx;

    public JdbcRunnerStore(JdbcTemplate db, ObjectMapper json, JdbcStoreProperties properties) {
        this.db = db;
        this.json = json;
        app = properties.requireApplicationId();
        tx = new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public List<RunnerInfo> runners() {
        return db.query(
                "select info_json, last_seen from tool_runners where application_id = ? order by runner_id",
                (r, n) -> {
                    RunnerInfo info = decode(r.getString(1), RunnerInfo.class);
                    info.lastSeen = r.getLong(2);
                    return info;
                },
                app);
    }

    public RunnerInfo runner(String id) {
        List<RunnerInfo> values =
                db.query(
                        "select info_json, last_seen from tool_runners where application_id = ? and runner_id = ?",
                        (r, n) -> {
                            RunnerInfo info = decode(r.getString(1), RunnerInfo.class);
                            info.lastSeen = r.getLong(2);
                            return info;
                        },
                        app,
                        id);
        return values.isEmpty() ? null : values.get(0);
    }

    public String credentialHash(String id) {
        List<String> values =
                db.query(
                        "select credential_hash from tool_runners where application_id = ? and runner_id = ?",
                        (r, n) -> r.getString(1),
                        app,
                        id);
        return values.isEmpty() ? null : values.get(0);
    }

    public void register(RunnerInfo info, String hash) {
        if (credentialHash(info.runnerId) == null)
            db.update(
                    "insert into tool_runners (application_id, runner_id, credential_hash, info_json, last_seen) values (?, ?, ?, ?, ?)",
                    app,
                    info.runnerId,
                    hash,
                    encode(info),
                    System.currentTimeMillis());
        else
            db.update(
                    "update tool_runners set info_json = ?, last_seen = ? where application_id = ? and runner_id = ?",
                    encode(info),
                    System.currentTimeMillis(),
                    app,
                    info.runnerId);
    }

    public void heartbeat(String id, long now) {
        db.update(
                "update tool_runners set last_seen = ? where application_id = ? and runner_id = ?",
                now,
                app,
                id);
    }

    public void bind(String session, String runner) {
        db.update(
                "insert into runner_sessions (application_id, session_id, runner_id) values (?, ?, ?)",
                app,
                session,
                runner);
    }

    public String runnerForSession(String session) {
        List<String> values =
                db.query(
                        "select runner_id from runner_sessions where application_id = ? and session_id = ?",
                        (r, n) -> r.getString(1),
                        app,
                        session);
        return values.isEmpty() ? null : values.get(0);
    }

    public RunnerProject project(String id) {
        List<RunnerProject> values =
                db.query(
                        "select config_json from runner_projects where application_id = ? and project_id = ?",
                        (r, n) -> decode(r.getString(1), RunnerProject.class),
                        app,
                        id);
        if (!values.isEmpty()) return values.get(0);
        // Existing chats already store their terminal and workspace. Preserve that project
        // when opening a database created before per-project tool selection was introduced.
        List<RunnerProject> previous =
                db.query(
                        "select s.workspace_path, r.runner_id from sessions s join runner_sessions r on s.application_id = r.application_id and s.session_id = r.session_id where s.application_id = ? and s.project_id = ? order by s.created_at",
                        (r, n) -> {
                            RunnerProject project = new RunnerProject();
                            project.projectId = id;
                            project.runnerId = r.getString("runner_id");
                            project.workspace = r.getString("workspace_path");
                            return project;
                        },
                        app,
                        id);
        if (previous.isEmpty()) return null;
        RunnerProject project = previous.get(0);
        RunnerInfo info = runner(project.runnerId);
        if (info != null)
            for (ToolDescriptor tool : info.tools) project.enabledTools.add(tool.name);
        saveProject(project);
        return project;
    }

    public void saveProject(RunnerProject project) {
        tx.execute(
                status -> {
                    if (db.update(
                                    "update runner_projects set config_json = ? where application_id = ? and project_id = ?",
                                    encode(project),
                                    app,
                                    project.projectId)
                            == 0)
                        db.update(
                                "insert into runner_projects (application_id, project_id, config_json) values (?, ?, ?)",
                                app,
                                project.projectId,
                                encode(project));
                    return null;
                });
    }

    public void submit(RunnerJob job) {
        tx.execute(
                status -> {
                    int locked =
                            db.update(
                                    "update runner_sessions set active_run_id = ? where application_id = ? and session_id = ? and runner_id = ? and active_run_id is null",
                                    job.request.runId,
                                    app,
                                    job.request.sessionId,
                                    job.runnerId);
                    if (locked != 1)
                        throw new IllegalStateException(
                                "This session already has an unfinished runner job");
                    db.update(
                            "insert into runner_jobs (application_id, run_id, runner_id, session_id, request_json, workspace, status, cancelled, created_at) values (?, ?, ?, ?, ?, ?, 'QUEUED', 0, ?)",
                            app,
                            job.request.runId,
                            job.runnerId,
                            job.request.sessionId,
                            encode(job.request),
                            job.workspace == null ? "" : job.workspace,
                            System.currentTimeMillis());
                    return null;
                });
    }

    private RunnerJob row(java.sql.ResultSet r) throws java.sql.SQLException {
        RunnerJob j = new RunnerJob();
        j.runnerId = r.getString("runner_id");
        j.workspace = r.getString("workspace");
        if (j.workspace != null && j.workspace.isEmpty()) j.workspace = null;
        j.request = decode(r.getString("request_json"), ChatRequest.class);
        j.status = r.getString("status");
        j.cancelled = r.getInt("cancelled") != 0;
        String response = r.getString("response_json");
        if (response != null) j.response = decode(response, ChatResponse.class);
        return j;
    }

    public RunnerJob job(String id) {
        List<RunnerJob> jobs =
                db.query(
                        "select * from runner_jobs where application_id = ? and run_id = ?",
                        (r, n) -> row(r),
                        app,
                        id);
        return jobs.isEmpty() ? null : jobs.get(0);
    }

    public List<RunnerJob> unfinished(String runner) {
        return db.query(
                "select * from runner_jobs where application_id = ? and runner_id = ? and status in ('QUEUED','CLAIMED') order by created_at",
                (r, n) -> row(r),
                app,
                runner);
    }

    public boolean claim(String run) {
        return db.update(
                        "update runner_jobs set status = 'CLAIMED' where application_id = ? and run_id = ? and status = 'QUEUED' and cancelled = 0",
                        app,
                        run)
                == 1;
    }

    public void reopen(String run) {
        tx.execute(
                status -> {
                    RunnerJob job = job(run);
                    if (job == null || !"INTERRUPTED".equals(job.status))
                        throw new IllegalStateException("Only interrupted jobs can be recovered");
                    if (db.update(
                                    "update runner_sessions set active_run_id = ? where application_id = ? and session_id = ? and active_run_id is null",
                                    run,
                                    app,
                                    job.request.sessionId)
                            != 1) throw new IllegalStateException("Session has another active job");
                    if (db.update(
                                    "update runner_jobs set status = 'CLAIMED', response_json = null where application_id = ? and run_id = ? and status = 'INTERRUPTED'",
                                    app,
                                    run)
                            != 1) throw new IllegalStateException("Job changed while recovering");
                    return null;
                });
    }

    public void cancel(String run) {
        db.update(
                "update runner_jobs set cancelled = 1 where application_id = ? and run_id = ?",
                app,
                run);
    }

    public void complete(String run, ChatResponse response) {
        tx.execute(
                status -> {
                    db.update(
                            "update runner_jobs set status = ?, response_json = ? where application_id = ? and run_id = ? and status in ('QUEUED','CLAIMED')",
                            response.status,
                            encode(response),
                            app,
                            run);
                    db.update(
                            "update runner_sessions set active_run_id = null where application_id = ? and active_run_id = ?",
                            app,
                            run);
                    return null;
                });
    }
}
