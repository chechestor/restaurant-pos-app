$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $adb)) { throw "adb �� ������: $adb" }

$apks = @(Get-ChildItem -LiteralPath $PSScriptRoot -File -Filter *.apk)
if ($apks.Count -ne 1) { throw "����� �� �������� ������ ������ ���� APK." }

& $adb start-server
if ($LASTEXITCODE -ne 0) { throw "adb �� ����������" }

$ready = @(& $adb devices | Select-String "`tdevice$")
if ($ready.Count -ne 1) {
  & $adb devices
  throw "����� ���� ������� �� �������� device. ������ ������� �� USB � ��������� ������ �� ������."
}

$apk = $apks[0].FullName
& $adb install -r $apk
if ($LASTEXITCODE -ne 0) { throw "��������� �� �������" }
Write-Host "�����������: $apk"
