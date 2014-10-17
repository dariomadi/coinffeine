;This file will be executed next to the application bundle image
;I.e. current directory will contain folder Coinffeine with application files
[Setup]
AppId={{Coinffeine}}
AppName=Coinffeine
AppVersion=0.1
AppVerName=Coinffeine 0.1
AppPublisher=Coinffeine S.L.
AppComments=Coinffeine
AppCopyright=
AppPublisherURL=http://coinffeine.com/
AppSupportURL=http://coinffeine.com/
AppUpdatesURL=http://coinffeine.com/
DefaultDirName={pf}\Coinffeine
DisableStartupPrompt=Yes
DisableProgramGroupPage=Yes
DisableReadyPage=Yes
DefaultGroupName=Coinffeine
;Optional License
LicenseFile=
;WinXP or above
MinVersion=0,5.1 
OutputBaseFilename=Coinffeine-0.1
Compression=lzma
SolidCompression=yes
SetupIconFile=Coinffeine\Coinffeine.ico
UninstallDisplayIcon={app}\Coinffeine.ico
UninstallDisplayName=Coinffeine
WizardImageStretch=No
WizardSmallImageFile=Coinffeine-setup-icon.bmp   
ArchitecturesInstallIn64BitMode=x64

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Files]
Source: "Coinffeine\Coinffeine.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "Coinffeine\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "C:\Windows\Temp\msvcp100.dll"; DestDir: "{app}"; Flags: ignoreversion allowunsafefiles

[Icons]
Name: "{group}\Coinffeine"; Filename: "{app}\Coinffeine.exe"; IconFilename: "{app}\Coinffeine.ico"; Check: returnTrue()
Name: "{commondesktop}\Coinffeine"; Filename: "{app}\Coinffeine.exe";  IconFilename: "{app}\Coinffeine.ico"; Check: returnFalse()

[Run]
Filename: "{app}\Coinffeine.exe"; Description: "{cm:LaunchProgram,Coinffeine}"; Flags: nowait postinstall skipifsilent; Check: returnTrue()
Filename: "{app}\Coinffeine.exe"; Parameters: "-install -svcName ""Coinffeine"" -svcDesc ""Copyright (c) 2013, 2014 Coinffeine S.L."" -mainExe ""Coinffeine.exe""  "; Check: returnFalse()

[UninstallRun]
Filename: "{app}\Coinffeine.exe "; Parameters: "-uninstall -svcName Coinffeine -stopOnUninstall"; Check: returnFalse()

[Code]
function returnTrue(): Boolean;
begin
  Result := True;
end;

function returnFalse(): Boolean;
begin
  Result := False;
end;

function InitializeSetup(): Boolean;
begin
// Possible future improvements:
//   if version less or same => just launch app
//   if upgrade => check if same app is running and wait for it to exit
//   Add pack200/unpack200 support? 
  Result := True;
end;  
