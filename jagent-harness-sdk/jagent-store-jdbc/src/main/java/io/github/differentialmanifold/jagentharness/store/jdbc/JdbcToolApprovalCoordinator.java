package io.github.differentialmanifold.jagentharness.store.jdbc;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.StopRequestedException;
import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalCoordinator;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalDecision;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class JdbcToolApprovalCoordinator implements ToolApprovalCoordinator {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_DENIED = "DENIED";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final String applicationId;
    private final long pollIntervalMillis;
    private final RowMapper<ApprovalRow> approvalRowMapper = (resultSet, rowNum) -> new ApprovalRow(
            resultSet.getString("status"),
            resultSet.getString("decision_reason"));

    public JdbcToolApprovalCoordinator(JdbcTemplate jdbcTemplate,
                                       ObjectMapper objectMapper,
                                       JdbcStoreProperties storeProperties,
                                       JdbcToolApprovalProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.applicationId = storeProperties.requireApplicationId();
        this.pollIntervalMillis = positive(properties.getPollIntervalMillis(), "pollIntervalMillis");
    }

    @Override
    public ToolApprovalDecision awaitDecision(String runId,
                                              String sessionId,
                                              ToolApprovalRequest request,
                                              StopSignal stopSignal,
                                              Runnable onPending) throws Exception {
        insertApproval(runId, sessionId, request);
        try {
            if (onPending != null) {
                onPending.run();
            }
        } catch (RuntimeException e) {
            cancelApproval(runId, request.getApprovalId(), "Tool approval was cancelled");
            throw e;
        }
        try {
            while (true) {
                if (stopSignal != null) {
                    stopSignal.throwIfAborted();
                }

                ApprovalRow row = findApproval(runId, request.getApprovalId());
                if (row == null) {
                    return ToolApprovalDecision.denied("Tool approval was not found");
                }
                if (STATUS_APPROVED.equals(row.status)) {
                    return ToolApprovalDecision.approved(row.decisionReason);
                }
                if (STATUS_DENIED.equals(row.status)) {
                    return ToolApprovalDecision.denied(defaultReason(
                            row.decisionReason,
                            "Tool approval was denied"));
                }
                if (STATUS_CANCELLED.equals(row.status)) {
                    return ToolApprovalDecision.denied(defaultReason(
                            row.decisionReason,
                            "Tool approval was cancelled"));
                }

                sleep();
            }
        } catch (StopRequestedException e) {
            cancelApproval(runId, request.getApprovalId(), "Tool approval was cancelled");
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (stopSignal != null) {
                stopSignal.throwIfAborted();
            }
            cancelApproval(runId, request.getApprovalId(), "Tool approval was cancelled");
            throw e;
        }
    }

    @Override
    public boolean resolve(String runId, String approvalId, boolean approved, String reason) {
        long now = System.currentTimeMillis();
        int updated = jdbcTemplate.update(
                "update agent_approvals "
                        + "set status = ?, decision_reason = ?, resolved_at = ?, updated_at = ? "
                        + "where application_id = ? and run_id = ? and approval_id = ? and status = ?",
                approved ? STATUS_APPROVED : STATUS_DENIED,
                reason == null ? "" : reason,
                now,
                now,
                applicationId,
                runId,
                approvalId,
                STATUS_PENDING);
        return updated > 0;
    }

    @Override
    public void cancelRun(String runId) {
        long now = System.currentTimeMillis();
        jdbcTemplate.update(
                "update agent_approvals "
                        + "set status = ?, decision_reason = ?, resolved_at = ?, updated_at = ? "
                        + "where application_id = ? and run_id = ? and status = ?",
                STATUS_CANCELLED,
                "Tool approval was cancelled",
                now,
                now,
                applicationId,
                runId,
                STATUS_PENDING);
    }

    private void insertApproval(String runId, String sessionId, ToolApprovalRequest request) {
        long now = System.currentTimeMillis();
        try {
            jdbcTemplate.update(
                    "insert into agent_approvals "
                            + "(application_id, run_id, approval_id, session_id, tool_call_id, tool_name, status, "
                            + "title, message, action, target, metadata_json, decision_reason, "
                            + "created_at, updated_at, resolved_at) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    applicationId,
                    runId,
                    request.getApprovalId(),
                    sessionId,
                    request.getToolCallId(),
                    request.getToolName(),
                    STATUS_PENDING,
                    request.getTitle(),
                    request.getMessage(),
                    request.getAction(),
                    request.getTarget(),
                    writeMetadata(request.getMetadata()),
                    null,
                    now,
                    now,
                    null);
        } catch (DataAccessException e) {
            if (findApproval(runId, request.getApprovalId()) != null) {
                throw new IllegalStateException(
                        "Tool approval already exists: " + runId + "/" + request.getApprovalId(),
                        e);
            }
            throw e;
        }
    }

    private ApprovalRow findApproval(String runId, String approvalId) {
        List<ApprovalRow> rows = jdbcTemplate.query(
                "select status, decision_reason from agent_approvals "
                        + "where application_id = ? and run_id = ? and approval_id = ?",
                approvalRowMapper,
                applicationId,
                runId,
                approvalId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void cancelApproval(String runId, String approvalId, String reason) {
        long now = System.currentTimeMillis();
        jdbcTemplate.update(
                "update agent_approvals "
                        + "set status = ?, decision_reason = ?, resolved_at = ?, updated_at = ? "
                        + "where application_id = ? and run_id = ? and approval_id = ? and status = ?",
                STATUS_CANCELLED,
                reason == null ? "" : reason,
                now,
                now,
                applicationId,
                runId,
                approvalId,
                STATUS_PENDING);
    }

    private String writeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize approval metadata", e);
        }
    }

    private void sleep() throws InterruptedException {
        Thread.sleep(pollIntervalMillis);
    }

    private static String defaultReason(String reason, String fallback) {
        return reason == null || reason.trim().isEmpty() ? fallback : reason;
    }

    private static long positive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return value;
    }

    private static class ApprovalRow {

        private final String status;
        private final String decisionReason;

        private ApprovalRow(String status, String decisionReason) {
            this.status = status;
            this.decisionReason = decisionReason;
        }
    }
}
