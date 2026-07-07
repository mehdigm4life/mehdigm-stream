script_name('MHD Invisible')
script_version('1.0')
script_version_number(100)
script_author('MHD')
script_description('Professional invisibility toggle for SA-MP Android. Type /mhdinvis to toggle.')

local enabled = false

local function applyInvisibility()
  if isCharInAnyCar(PLAYER_PED) then
    local car = storeCarCharIsInNoSave(PLAYER_PED)
    setCarVisible(car, false)
  end
  setCharVisible(PLAYER_PED, false)
end

local function removeInvisibility()
  setCharVisible(PLAYER_PED, true)
  if isCharInAnyCar(PLAYER_PED) then
    local car = storeCarCharIsInNoSave(PLAYER_PED)
    setCarVisible(car, true)
  end
end

function main()
  while not isSampLoaded() do wait(100) end
  while not isSampAvailable() do wait(100) end
  wait(1000)

  sampRegisterChatCommand('mhdinvis', function()
    enabled = not enabled
    if enabled then
      applyInvisibility()
      sampAddChatMessage('{FFFFFF}[MHD] {00FF00}Invisible {FFFFFF}enabled', -1)
      printStringNow('~g~Invisible ~w~ON', 1500)
    else
      removeInvisibility()
      sampAddChatMessage('{FFFFFF}[MHD] {FF0000}Invisible {FFFFFF}disabled', -1)
      printStringNow('~r~Invisible ~w~OFF', 1500)
    end
  end)

  while true do
    wait(0)
    if enabled then
      applyInvisibility()
    end
  end
end
