script_name('MHD God Mode Pro')
script_version('3.0')
script_version_number(300)
script_author('MHD')
script_description('Ultimate God Mode & Anti-Fall for SA-MP. Type /mhdgod to toggle.')

local godmode = false
local targetHealth = 100
local targetArmour = 100
local sampev = require 'samp.events'

function sampev.onTakeDamage(from, damage, weapon, bodypart)
  return not godmode
end

function sampev.onSetPlayerHealth(hp)
  if godmode and hp < targetHealth then
    return false
  end
  return true
end

function sampev.onSetPlayerArmour(arm)
  if godmode and arm < targetArmour then
    setPlayerArmour(PLAYER_PED, targetArmour)
    return false
  end
  return true
end

function main()
  while not isSampLoaded() do wait(100) end
  while not isSampAvailable() do wait(100) end
  wait(1000)

  sampRegisterChatCommand('mhdgod', function()
    godmode = not godmode
    if godmode then
      setPlayerHealth(PLAYER_PED, targetHealth)
      setPlayerArmour(PLAYER_PED, targetArmour)
      sampAddChatMessage('{FFFFFF}[MHD] {00FF00}God Mode & Anti-Fall Enabled', -1)
      printStringNow('~g~God Mode ~w~ON', 1500)
    else
      sampAddChatMessage('{FFFFFF}[MHD] {FF0000}God Mode Disabled', -1)
      printStringNow('~r~God Mode ~w~OFF', 1500)
    end
  end)

  while true do
    wait(0)
    if godmode then
      setPlayerHealth(PLAYER_PED, targetHealth)
      setPlayerArmour(PLAYER_PED, targetArmour)
      if isCharInAnyCar(PLAYER_PED) then
        local car = storeCarCharIsInNoSave(PLAYER_PED)
        if car and doesVehicleExist(car) then
          setCarHealth(car, 1000)
        end
      end
    end
  end
end
