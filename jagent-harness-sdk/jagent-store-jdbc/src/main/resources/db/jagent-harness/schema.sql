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

create table if not exists messages (
    id integer primary key autoincrement,
    application_id varchar(128) not null,
    message_id varchar(64) not null,
    session_id varchar(64) not null,
    turn_id varchar(64),
    parent_message_id varchar(64),
    role varchar(32) not null,
    content text,
    reasoning_content text,
    tool_call_id varchar(128),
    tool_name varchar(128),
    tool_calls_json text,
    stop_reason varchar(32),
    created_at varchar(64) not null
);

create unique index if not exists ux_messages_application_message
    on messages (application_id, message_id);
create index if not exists ix_messages_application_session
    on messages (application_id, session_id);

create table if not exists timeline_events (
    id integer primary key autoincrement,
    application_id varchar(128) not null,
    event_id varchar(64) not null,
    session_id varchar(64) not null,
    turn_id varchar(64),
    type varchar(128) not null,
    payload_json text,
    created_at varchar(64) not null
);

create unique index if not exists ux_timeline_events_application_event
    on timeline_events (application_id, event_id);
create index if not exists ix_timeline_events_application_session
    on timeline_events (application_id, session_id);

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
    request_id varchar(128) not null,
    session_id varchar(64) not null,
    status varchar(32) not null,
    created_at bigint not null,
    updated_at bigint not null
);

create unique index if not exists ux_agent_runs_application_request
    on agent_runs (application_id, request_id);

create table if not exists agent_approvals (
    id integer primary key autoincrement,
    application_id varchar(128) not null,
    request_id varchar(128) not null,
    approval_id varchar(128) not null,
    session_id varchar(64) not null,
    tool_call_id varchar(128),
    tool_name varchar(128),
    status varchar(32) not null,
    title varchar(512),
    message text,
    action varchar(128),
    target varchar(2048),
    metadata_json text,
    decision_reason text,
    created_at bigint not null,
    updated_at bigint not null,
    resolved_at bigint
);

create unique index if not exists ux_agent_approvals_request_approval
    on agent_approvals (application_id, request_id, approval_id);
create index if not exists ix_agent_approvals_request_status
    on agent_approvals (application_id, request_id, status);
