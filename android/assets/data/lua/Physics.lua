-- the collisions table holds the callback dispatcher 
-- and the callback functions for physics collisions
collisions = {}

-- this resolver simply sends both bodies to the dispatcher
-- twice in opposite ordering A and B then B and A
-- this allows the effects of the collision to be handled neatly
function collisions.resolver(bodyA, bodyB)
  collisions.dispatch(bodyA, bodyB)
  collisions.dispatch(bodyB, bodyA)
end

-- dispatch the body to the callback based on its type
function collisions.dispatch(body, other)

  local bodyFlag = body:getContactCallbackFlag()
  local otherFlag = other:getContactCallbackFlag()
  
  if ContactCallbackFlags:compare(bodyFlag, ContactCallbackFlags.TRIGGER) then
    collisions.trigger(body, other)
  end
  
  if ContactCallbackFlags:compare(bodyFlag, ContactCallbackFlags.TEAM_CFP) then
    -- do nothing with players
  end
  
  if ContactCallbackFlags:compare(bodyFlag, ContactCallbackFlags.TEAM_BOT) then
    -- do nothing with enemies
  end
  
  if ContactCallbackFlags:compare(bodyFlag, ContactCallbackFlags.TEAM_DMC) then
    -- do nothing with enemies
  end
  
  if ContactCallbackFlags:compare(bodyFlag, ContactCallbackFlags.TEAM_PIRATE) then
    -- do nothing with enemies
  end
  
  if ContactCallbackFlags:compare(bodyFlag, ContactCallbackFlags.HEALTH_PACK) then
    collisions.healthPack(body, other)
  end
  
  if ContactCallbackFlags:compare(bodyFlag, ContactCallbackFlags.POWER_CORE) then
    collisions.powerCore(body, other)
  end
  
  if ContactCallbackFlags:compare(bodyFlag, ContactCallbackFlags.SMALL_GEM) then
    collisions.smallGem(body, other)
  end
  
  if ContactCallbackFlags:compare(bodyFlag, ContactCallbackFlags.LARGE_GEM) then
    collisions.largeGem(body, other)
  end
  
  if ContactCallbackFlags:compare(bodyFlag, ContactCallbackFlags.SPECIAL_POWER) then
    collisions.specialPower(body, other)
  end
  
  if ContactCallbackFlags:compare(bodyFlag, ContactCallbackFlags.AI_SENSOR) then
    collisions.aiSensor(body, other)
  end
  
end

function collisions.trigger(trigger, activator)
  
  local entity = trigger.userData
  local trig = em:getComponent(entity, Trigger)
  
  if not (trig == nil) then
    -- call the function name stored in the trigger component
    triggers[trig.name](entity)
  end
end

-- try to apply the health pack to the recipient
function collisions.healthPack(healthPack, recipient)

  -- update the recipients health component
  local entity = recipient.userData
  local health = em:getComponent(entity, Health)
  
  if not (health == nil) then
        
    -- if recipient is max health then don't pick up the pack
    if health.currentHealth < health.maxHealth then
      
      -- we use reflection to get around the fact there is only read access on member variables
      components.set(health, "currentHealth", (health.currentHealth + 35))
                
      if health.currentHealth > health.maxHealth then
        components.set(health, "currentHealth", health.maxHealth)
      end
      
      -- the health pack was picked up so we remove it from the simulation
      em:recycle(healthPack.userData)
      
    end
  end
end

function collisions.powerCore(powerCore, recipient)
  -- update the recipients health component
  local entity = recipient.userData
  local stats = em:getComponent(entity, PlayerStats)
  
  if not (stats == nil) then
    components.set(stats, "cores", (stats.cores + 1))
    em:recycle(powerCore.userData)
  end
end

function collisions.smallGem(gem, recipient)
  -- update the recipients health component
  local entity = recipient.userData
  local stats = em:getComponent(entity, PlayerStats)
  
  if not (stats == nil) then
    components.set(stats, "gems", (stats.gems + 5))
    em:recycle(gem.userData)
  end
end

function collisions.largeGem(gem, recipient)
  -- update the recipients health component
  local entity = recipient.userData
  local stats = em:getComponent(entity, PlayerStats)
  
  if not (stats == nil) then
    components.set(stats, "gems", (stats.gems + 10))
    em:recycle(gem.userData)
  end
end

function collisions.specialPower(specialPower, recipient)
  -- update the recipients health component
  local entity = recipient.userData
  local stats = em:getComponent(entity, PlayerStats)
  
  if not (stats == nil) then
    components.set(stats, "specials", (stats.specials + 1))
    em:recycle(specialPower.userData)
  end
end

-- Due to the way the sensor is set up to contact only
-- certain groups, the body collided with is always a threat 
-- to the brain and can be added to the threat list
function collisions.aiSensor(sensor, threat)
  
  debug("threat added")
  
  local threatEntity = threat.userData
  local sensorEntity = sensor.userData
  local parentComponent = em:getComponent(sensorEntity, Parent)
  local parentEntity = em:getEntityByID(parentComponent.parentId) 
    
  local brain = aiManager:getBrain(parentEntity:getId())
  local threatAmount = 0
  brain:increaseThreat(threatEntity, threatAmount)

end
