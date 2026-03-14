$ServerIP = "132.145.47.61"
$User = "ubuntu"
$KeyPath = "C:\Users\Stuart\Documents\ssh-key-2026-03-06.key"

# The -t flag is required to attach to a screen session
ssh -t -i $KeyPath $User@$ServerIP "screen -d -r minecraft"