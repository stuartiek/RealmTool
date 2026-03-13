# --- CONFIGURATION ---
$ServerIP = "132.145.47.61"  # <--- YOUR ORACLE CLOUD IP
$User = "ubuntu"
$KeyPath = "C:\Users\Stuart\Documents\ssh-key-2026-03-06.key"
$ProjectDir = "c:\Users\Stuart\Documents\RealmTool"
$RemoteDir = "~/minecraft"

$ErrorActionPreference = "Stop"

if (-not (Test-Path $KeyPath)) {
    Write-Host "Error: SSH Key file not found at: $KeyPath" -ForegroundColor Red
    exit 1
}

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
# 1. Force kill any running java server processes (ensures session.lock is released)
# 2. Wipe dead screen sessions from the registry
# 3. Delete old plugin jars
$RemoteStopCmd = "pkill -f server.jar; screen -wipe; echo 'Cleaning old plugin versions...'; rm -f $RemoteDir/plugins/DrowsyManagementTool*.jar"

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
