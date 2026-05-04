local key = KEYS[1]
local recordValue = ARGV[1]
local ttl = tonumber(ARGV[2])

-- Atomic Check and Set
local current = redis.call('GET', key)

if current then
    -- Key exists: Return the existing JSON string
    return current
else
    -- Key missing: Reserve it with PROCESSING status
    redis.call('SET', key, recordValue, 'EX', ttl)
    -- Return nil so Java knows it's the first execution
    return nil
end