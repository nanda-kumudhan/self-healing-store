ALTER TABLE node_registrations ADD COLUMN last_seen TIMESTAMP WITH TIME ZONE;
CREATE TABLE objects (
 id UUID PRIMARY KEY, owner VARCHAR(64) NOT NULL, header TEXT NOT NULL,
 committed BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE chunks (
 object_id UUID NOT NULL REFERENCES objects(id), chunk_index INTEGER NOT NULL,
 metadata TEXT NOT NULL, verified BOOLEAN NOT NULL DEFAULT FALSE, retry_after TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(object_id, chunk_index)
);
CREATE TABLE shard_assignments (
 id UUID PRIMARY KEY, object_id UUID NOT NULL, chunk_index INTEGER NOT NULL,
 shard_index INTEGER NOT NULL, node_id UUID NOT NULL REFERENCES node_registrations(id),
 pending_node_id UUID REFERENCES node_registrations(id), damaged BOOLEAN NOT NULL DEFAULT FALSE,
 expected_hash VARCHAR(64) NOT NULL, shard_bytes INTEGER NOT NULL,
 FOREIGN KEY(object_id, chunk_index) REFERENCES chunks(object_id, chunk_index),
 UNIQUE(object_id, chunk_index, shard_index)
);
CREATE INDEX shard_node ON shard_assignments(node_id);
CREATE INDEX objects_committed ON objects(committed);
