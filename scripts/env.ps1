# Local Windows build environment for halo-chat.
# Usage:  . .\scripts\env.ps1    (dot-source it)
#
# Why this exists:
#   * The default `java` on PATH is broken on this machine, use JDK 21.
#   * The JDK does not trust the Windows root CA store by default; Windows
#     installations behind a TLS-inspecting proxy/virus scanner need
#     trustStoreType=WINDOWS-ROOT for Gradle's HTTPS downloads.
#   * pnpm 10.33.0 is provisioned into a local prefix instead of relying on
#     corepack (corepack 0.34 failed to install that exact version here).
# Cross-platform users only need JDK 21 + Node 22.12+ + pnpm 10.

$JdkHome = 'C:\Program Files\Java\jdk-21.0.10'
$PnpmBin = 'E:\opencodeproject\halo-scratch\npm-global\node_modules\.bin'

$env:JAVA_HOME = $JdkHome
$env:JAVA_OPTS = '-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT'
$env:GRADLE_OPTS = '-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT'
$env:COREPACK_ENABLE_PROJECT_SPEC = '0'
# Keep pnpm's content store inside the project scratch area. The default store
# under %LOCALAPPDATA%\pnpm is blocked by file permissions/AV on this machine;
# copy mode avoids hard link privileges as well.
$env:npm_config_store_dir = 'E:\opencodeproject\halo-scratch\pnpm-store'
$env:npm_config_package_import_method = 'copy'

$newPath = @($PnpmBin, "$JdkHome\bin")
$newPath += ($env:Path -split ';' | Where-Object { $_ -and $_ -notmatch 'javapath' })
$env:Path = ($newPath -join ';')
