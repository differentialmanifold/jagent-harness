create table if not exists sessions (
    id integer primary key autoincrement,
    session_id varchar(64) not null,
    title varchar(512),
    workspace_path varchar(1024),
    status varchar(32) not null,
    metadata_json text,
    created_at varchar(64) not null,
    updated_at varchar(64) not null
);

create unique index if not exists ux_sessions_session_id on sessions (session_id);

create table if not exists compaction_states (
    id integer primary key autoincrement,
    session_id varchar(64) not null,
    summary text,
    cursor_message_id varchar(64),
    version bigint not null,
    metadata_json text,
    updated_at varchar(64) not null
);

create unique index if not exists ux_compaction_states_session_id on compaction_states (session_id);

create table if not exists messages (
    id integer primary key autoincrement,
    message_id varchar(64) not null,
    session_id varchar(64) not null,
    turn_id varchar(64),
    parent_message_id varchar(64),
    role varchar(32) not null,
    content text,
    tool_call_id varchar(128),
    tool_name varchar(128),
    tool_calls_json text,
    stop_reason varchar(32),
    metadata_json text,
    created_at varchar(64) not null
);

create unique index if not exists ux_messages_message_id on messages (message_id);

create table if not exists timeline_events (
    id integer primary key autoincrement,
    event_id varchar(64) not null,
    session_id varchar(64) not null,
    turn_id varchar(64),
    type varchar(128) not null,
    payload_json text,
    created_at varchar(64) not null
);

create unique index if not exists ux_timeline_events_event_id on timeline_events (event_id);

create table if not exists knowledge_files (
    id integer primary key autoincrement,
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

create unique index if not exists ux_knowledge_files_path on knowledge_files (path);
create index if not exists ix_knowledge_files_parent_path on knowledge_files (parent_path);
create index if not exists ix_knowledge_files_name on knowledge_files (name);
create index if not exists ix_knowledge_files_node_type on knowledge_files (node_type);

create table if not exists skill_manifests (
    id integer primary key autoincrement,
    skill_key varchar(256) not null,
    skill_dir_path varchar(1024) not null,
    skill_file_path varchar(1024) not null,
    name varchar(256),
    description text,
    created_at varchar(64) not null,
    updated_at varchar(64) not null
);

create unique index if not exists ux_skill_manifests_skill_key on skill_manifests (skill_key);
create unique index if not exists ux_skill_manifests_file_path on skill_manifests (skill_file_path);
create index if not exists ix_skill_manifests_dir_path on skill_manifests (skill_dir_path);

create table if not exists prompt_bindings (
    id integer primary key autoincrement,
    prompt_name varchar(128) not null,
    file_path varchar(1024) not null,
    priority integer not null,
    created_at varchar(64) not null,
    updated_at varchar(64) not null
);

create unique index if not exists ux_prompt_bindings_file_path on prompt_bindings (file_path);
create index if not exists ix_prompt_bindings_prompt_name on prompt_bindings (prompt_name, priority);

create table if not exists agent_runs (
    request_id varchar(128) primary key,
    session_id varchar(64) not null,
    owner_instance_id varchar(64) not null,
    status varchar(32) not null,
    lease_until bigint not null,
    created_at bigint not null,
    updated_at bigint not null
);

create index if not exists ix_agent_runs_lease_until on agent_runs (lease_until);
