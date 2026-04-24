-- scripts/idempotency_check.lua
local key = KEYS[1]
local recordValue = ARGV[1]
local ttl = tonumber(ARGV[2])

-- Try to get existing record
local current = redis.call('GET', key)

if current then
    -- Record exists, return it to Java
    return current
else
    -- No record, set the "PROCESSING" status with TTL
    redis.call('SET', key, recordValue, 'EX', ttl)
    -- Return nil to signify we are the first ones here
    return nil
end