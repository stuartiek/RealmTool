# --- CONFIGURATION ---
$ServerIP = "132.145.47.61"  # <--- YOUR ORACLE CLOUD IP
$User = "ubuntu"
$KeyPath = "C:\Users\Stuart\Downloads\ssh-key-2026-03-06.key"
$ProjectDir = "c:\Users\Stuart\Documents\RealmTool"
$RemoteDir = "~/minecraft"

$ErrorActionPreference = "Stop"

# --- 1. GET VERSION & BUILD ---
Write-Host "--- DrowsyTool Deployer ---" -ForegroundColor Cyan

# Read version from pom.xml automatically
if (Test-Path "$ProjectDir\pom.xml") {
    [xml]$pom = Get-Content "$ProjectDir\pom.xml"
    $Version = $pom.project.version
    $JarName = "DrowsyManagementTool-$Version.jar"
    Write-Host "Detected Version: $Version" -ForegroundColor Gray
} else {
    Write-Host "Error: pom.xml not found!" -ForegroundColor Red
    exit 1
}

Write-Host "`n[1/4] Building with Maven..." -ForegroundColor Yellow
Set-Location $ProjectDir
# Run Maven (cmd /c is used to ensure mvn executes correctly in PowerShell)
cmd /c mvn clean package

if ($LASTEXITCODE -ne 0) {
    Write-Host "Build Failed!" -ForegroundColor Red
    exit 1
}
Write-Host "Build Success!" -ForegroundColor Green

# --- 2. STOP SERVER & CLEAN ---
Write-Host "`n[2/4] Stopping remote server and cleaning old plugins..." -ForegroundColor Yellow

# Remote commands:
# 1. Try to send 'stop' to a screen session named 'minecraft' (if it exists)
# 2. Wait 10 seconds for graceful shutdown
# 3. Force kill java if it's still running (cleanup)
# 4. Delete any existing DrowsyManagementTool jars
$RemoteStopCmd = "screen -S minecraft -p 0 -X stuff 'stop\n' 2>/dev/null; sleep 10; pkill -f server.jar; rm $RemoteDir/plugins/DrowsyManagementTool*.jar"

ssh -i $KeyPath -o StrictHostKeyChecking=no $User@$ServerIP $RemoteStopCmd

# --- 3. UPLOAD ---
Write-Host "`n[3/4] Uploading $JarName..." -ForegroundColor Yellow
scp -i $KeyPath "$ProjectDir\target\$JarName" "$User@${ServerIP}:$RemoteDir/plugins/"

# --- 4. START SERVER ---
Write-Host "`n[4/4] Starting server..." -ForegroundColor Yellow

# Remote commands:
# 1. Go to minecraft folder
# 2. Start server in a detached screen session named 'minecraft'
$RemoteStartCmd = "cd $RemoteDir; screen -dmS minecraft java -Xmx16G -jar server.jar nogui"

ssh -i $KeyPath $User@$ServerIP $RemoteStartCmd

Write-Host "`nDeployment Complete! Server is restarting in screen session 'minecraft'." -ForegroundColor Green
