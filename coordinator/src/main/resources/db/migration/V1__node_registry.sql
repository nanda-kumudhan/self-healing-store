CREATE TABLE node_registrations (
    id UUID PRIMARY KEY,
    endpoint VARCHAR(2048) NOT NULL UNIQUE,
    registered_at TIMESTAMP WITH TIME ZONE NOT NULL
);
-- Shard ownership, manifests and repair state deliberately await the hands-on design.
