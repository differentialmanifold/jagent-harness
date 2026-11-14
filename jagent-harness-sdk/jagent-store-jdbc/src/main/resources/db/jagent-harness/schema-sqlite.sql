create table if not exists projects (
    id integer primary key autoincrement,
    application_id varchar(128) not null,
    project_id varchar(64) not null,
    name varchar(512),
    workspace_path varchar(1024),
    created_at varchar(64) not null,
    updated_at varchar(64) not null
);

create unique index if not exists ux_projects_application_project
    on projects (application_id, project_id);

create table if not exists sessions (
    id integer primary key autoincrement,
    application_id varchar(128) not null,
    session_id varchar(64) not null,
    title varchar(512),
    project_id varchar(64) not null,
    project_name varchar(512),
    workspace_path varchar(1024),
    status varchar(32) not null,
    metadata_json text,
    created_at varchar(64) not null,
    updated_at varchar(64) not null
);

create unique index if not exists ux_sessions_application_session
    on sessions (application_id, session_id);
create index if not exists ix_sessions_application_project_name
    on sessions (application_id, project_name);
create index if not exists ix_sessions_application_project
    on sessions (application_id, project_id);

create table if not exists compaction_states (
    id integer primary key autoincrement,
    application_id varchar(128) not null,
    session_id varchar(64) not null,
    summary text,
    cursor_message_id varchar(64),
    version bigint not null,
    metadata_json text,
    updated_at varchar(64) not null
);

create unique index if not exists ux_compaction_states_application_session
    on compaction_states (application_id, session_id);

create table if not exists model_call_usages (
    id integer primary key autoincrement,
    application_id varchar(128) not null,
    usage_id varchar(64) not null,
    session_id varchar(64) not null,
    run_id varchar(64) not null,
    turn_id varchar(64) not null,
    message_id varchar(64) not null,
    provider varchar(128),
    model varchar(256),
    context_window_tokens integer not null,
    threshold_tokens integer not null,
    estimate_source varchar(64),
    estimated_tokens integer,
    actual_context_tokens integer,
    prompt_tokens integer,
    completion_tokens integer,
    reasoning_tokens integer,
    cached_tokens integer,
    total_tokens integer,
    created_at varchar(64) not null
);

create unique index if not exists ux_model_call_usages_application_usage
    on model_call_usages (application_id, usage_id);
create index if not exists ix_model_call_usages_application_session
    on model_call_usages (application_id, session_id, id);
create index if not exists ix_model_call_usages_application_run_turn
    on model_call_usages (application_id, run_id, turn_id, id);

create table if not exists messages (
    id integer primary key autoincrement,
    application_id varchar(128) not null,
    message_id varchar(64) not null,
    session_id varchar(64) not null,
    run_id varchar(64) not null,
    turn_id varchar(64) not null,
    parent_message_id varchar(64),
    role varchar(32) not null,
    content text,
    images_json text,
    reasoning_content text,
    tool_call_id varchar(128),
    tool_name varchar(128),
    tool_calls_json text,
    stop_reason varchar(32),
    metadata_json text,
    created_at varchar(64) not null
);

create unique index if not exists ux_messages_application_message
    on messages (application_id, message_id);
create index if not exists ix_messages_application_session
    on messages (application_id, session_id);
create index if not exists ix_messages_application_run_turn
    on messages (application_id, run_id, turn_id, id);

create table if not exists timeline_events (
    id integer primary key autoincrement,
    application_id varchar(128) not null,
    event_id varchar(64) not null,
    session_id varchar(64) not null,
    run_id varchar(64) not null,
    turn_id varchar(64),
    type varchar(128) not null,
    payload_json text,
    created_at varchar(64) not null
);

create unique index if not exists ux_timeline_events_application_event
    on timeline_events (application_id, event_id);
create index if not exists ix_timeline_events_application_session
    on timeline_events (application_id, session_id);
create index if not exists ix_timeline_events_application_run_turn
    on timeline_events (application_id, run_id, turn_id, id);

create table if not exists knowledge_files (
    id integer primary key autoincrement,
    application_id varchar(128) not null,
    scope_type varchar(32) not null,
    scope_id varchar(64) not null,
    path varchar(1024) not null,
    parent_path varchar(1024) not null,
    name varchar(256) not null,
    node_type varchar(32) not null,
    content text not null,
    content_type varchar(128) not null,
    size bigint not null,
    content_hash varchar(128),
    created_at varchar(64) not null,
    updated_at varchar(64) not null
);

create unique index if not exists ux_knowledge_files_scope_path
    on knowledge_files (application_id, scope_type, scope_id, path);
create index if not exists ix_knowledge_files_application_parent
    on knowledge_files (application_id, scope_type, scope_id, parent_path);
create index if not exists ix_knowledge_files_application_name
    on knowledge_files (application_id, name);
create index if not exists ix_knowledge_files_application_node_type
    on knowledge_files (application_id, node_type);

create table if not exists skill_manifests (
    id integer primary key autoincrement,
    application_id varchar(128) not null,
    scope_type varchar(32) not null,
    scope_id varchar(64) not null,
    skill_key varchar(256) not null,
    skill_dir_path varchar(1024) not null,
    skill_file_path varchar(1024) not null,
    name varchar(256),
    description text,
    created_at varchar(64) not null,
    updated_at varchar(64) not null
);

create unique index if not exists ux_skill_manifests_scope_skill_key
    on skill_manifests (application_id, scope_type, scope_id, skill_key);
create unique index if not exists ux_skill_manifests_scope_file_path
    on skill_manifests (application_id, scope_type, scope_id, skill_file_path);
create index if not exists ix_skill_manifests_application_dir_path
    on skill_manifests (application_id, skill_dir_path);

create table if not exists agent_runs (
    id integer primary key autoincrement,
    application_id varchar(128) not null,
    run_id varchar(64) not null,
    session_id varchar(64) not null,
    status varchar(32) not null,
    created_at bigint not null,
    updated_at bigint not null
);

create unique index if not exists ux_agent_runs_application_run
    on agent_runs (application_id, run_id);

create table if not exists agent_active_runs (
    id integer primary key autoincrement,
    application_id varchar(128) not null,
    session_id varchar(64) not null,
    run_id varchar(64) not null,
    accepting_inputs integer not null,
    created_at bigint not null,
    updated_at bigint not null
);

create unique index if not exists ux_agent_active_runs_application_run
    on agent_active_runs (application_id, run_id);

create table if not exists agent_run_inputs (
    id integer primary key autoincrement,
    application_id varchar(128) not null,
    input_id varchar(128) not null,
    session_id varchar(64) not null,
    run_id varchar(64) not null,
    content text not null,
    images_json text,
    status varchar(32) not null,
    claimed_after_turn_id varchar(64),
    created_at bigint not null,
    updated_at bigint not null,
    claimed_at bigint
);

create unique index if not exists ux_agent_run_inputs_application_input
    on agent_run_inputs (application_id, input_id);
create index if not exists ix_agent_run_inputs_pending_run
    on agent_run_inputs (application_id, run_id, status, id);
create index if not exists ix_agent_run_inputs_claimed_boundary
    on agent_run_inputs (application_id, run_id, status, claimed_after_turn_id, id);

-- Remote tool runners: no changes to existing conversation tables.
create table if not exists tool_runners (
    application_id varchar(128) not null,
    runner_id varchar(128) not null,
    credential_hash varchar(64) not null,
    info_json text not null,
    last_seen bigint not null,
    primary key (application_id, runner_id)
);
create table if not exists runner_sessions (
    application_id varchar(128) not null,
    session_id varchar(128) not null,
    runner_id varchar(128) not null,
    active_run_id varchar(128),
    primary key (application_id, session_id)
);
create table if not exists runner_jobs (
    application_id varchar(128) not null,
    run_id varchar(128) not null,
    runner_id varchar(128) not null,
    session_id varchar(128) not null,
    request_json text not null,
    workspace varchar(2048) not null,
    status varchar(32) not null,
    cancelled integer not null,
    response_json text,
    created_at bigint not null,
    primary key (application_id, run_id)
);
create index if not exists ix_runner_jobs_pending on runner_jobs(application_id, runner_id, status);

create table if not exists runner_projects (
    application_id varchar(128) not null,
    project_id varchar(128) not null,
    config_json text not null,
    primary key (application_id, project_id)
);

-- Personal assistants are independent of ordinary projects.
create table if not exists personal_settings (
    application_id varchar(128) primary key,
    primary_agent_id varchar(64) not null
);

create table if not exists personal_agents (
    application_id varchar(128) not null,
    agent_id varchar(128) not null,
    revision bigint not null,
    body_json text not null,
    channel_json text,
    primary key (application_id, agent_id)
);
create table if not exists personal_conversations (
    application_id varchar(128) not null,
    session_id varchar(128) not null,
    agent_id varchar(128) not null,
    created_at bigint not null,
    body_json text not null,
    primary key (application_id, session_id)
);
create table if not exists personal_inbox (
    application_id varchar(128) not null,
    message_id varchar(128) not null,
    session_id varchar(128) not null,
    status varchar(32) not null,
    created_at bigint not null,
    body_json text not null,
    primary key (application_id, message_id)
);
create index if not exists ix_personal_inbox_pending on personal_inbox(application_id, status, created_at);
create index if not exists ix_personal_inbox_session on personal_inbox(application_id, session_id, created_at);
create table if not exists personal_cards (
    application_id varchar(128) not null,
    card_id varchar(128) not null,
    revision bigint not null,
    body_json text not null,
    primary key (application_id, card_id)
);

-- Reversible personal memory and skill edits, ordered by the Agent revision.
create table if not exists personal_learning_changes (
    application_id varchar(128) not null,
    agent_id varchar(128) not null,
    change_id varchar(128) not null,
    revision bigint not null,
    path varchar(1024) not null,
    body_json text not null,
    primary key (application_id, agent_id, change_id),
    unique (application_id, agent_id, revision)
);
create index if not exists ix_personal_learning_path on personal_learning_changes(application_id, agent_id, path, revision);

-- Session-level skill activations are independent of messages and compaction state.
create table if not exists skill_activations (
    application_id varchar(128) not null,
    session_id varchar(64) not null,
    scope_key varchar(256) not null,
    activation_id varchar(64) not null,
    activation_json text not null,
    primary key (application_id, session_id, scope_key, activation_id)
);

-- Model configuration and per-run selections are private server data.
create table if not exists model_settings (
    application_id varchar(128) primary key,
    revision bigint not null,
    payload_json text not null
);
create table if not exists run_models (
    application_id varchar(128) not null,
    session_id varchar(64) not null,
    run_id varchar(64) not null,
    payload_json text not null,
    primary key (application_id, session_id, run_id)
);
