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
