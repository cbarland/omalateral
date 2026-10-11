$ErrorActionPreference = 'Stop'
# Mirrors upstream's pinned fetch-proot.sh, including its checksum verification.
$projectRoot = Split-Path $PSScriptRoot
$output = Join-Path $projectRoot 'third_party/omalauncher/app/src/main/jniLibs/arm64-v8a'
$scratch = Join-Path $projectRoot ('build/proot-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $output,$scratch -Force | Out-Null
$packages = @(
    @('proot','pool/main/p/proot/proot_5.1.107.96_aarch64.deb','8199dca06dccb693ec09fb1759e3e1ad08b4863f0c11c612f89c20bd9ecdc1a0'),
    @('libtalloc','pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb','556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7'),
    @('libandroid-shmem','pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb','0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6')
)
foreach ($entry in $packages) {
    $dir = Join-Path $scratch $entry[0]
    New-Item -ItemType Directory $dir | Out-Null
    $deb = Join-Path $dir 'package.deb'
    Invoke-WebRequest ('https://packages.termux.dev/apt/termux-main/' + $entry[1]) -OutFile $deb
    if ((Get-FileHash $deb -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry[2]) { throw "Checksum mismatch: $($entry[0])" }
    & tar -xf $deb -C $dir
    if ($LASTEXITCODE) { throw 'Cannot extract deb archive' }
    $data = Get-ChildItem $dir -Filter 'data.tar.*' | Select-Object -First 1
    & tar -xf $data.FullName -C $dir
    if ($LASTEXITCODE) { throw 'Cannot extract package data' }
}
$prefix = 'data/data/com.termux/files/usr'
Copy-Item "$scratch/proot/$prefix/bin/proot" "$output/libproot.so"
Copy-Item "$scratch/proot/$prefix/libexec/proot/loader" "$output/libproot-loader.so"
$talloc = Get-ChildItem "$scratch/libtalloc/$prefix/lib" -Filter 'libtalloc.so.*' | Where-Object { -not $_.LinkType } | Select-Object -First 1
Copy-Item $talloc.FullName "$output/libtalloc.so"
Copy-Item "$scratch/libandroid-shmem/$prefix/lib/libandroid-shmem.so" "$output/libandroid-shmem.so"
foreach ($file in @("$output/libproot.so", "$output/libtalloc.so")) {
    $bytes = [IO.File]::ReadAllBytes($file)
    $old = [Text.Encoding]::ASCII.GetBytes("libtalloc.so.2`0")
    $new = [Text.Encoding]::ASCII.GetBytes("libtalloc.so`0`0`0")
    for ($i = 0; $i -le $bytes.Length - $old.Length; $i++) {
        $match = $true
        for ($j = 0; $j -lt $old.Length; $j++) { if ($bytes[$i+$j] -ne $old[$j]) { $match = $false; break } }
        if ($match) { [Array]::Copy($new,0,$bytes,$i,$new.Length) }
    }
    [IO.File]::WriteAllBytes($file,$bytes)
}
Write-Output "Verified PRoot libraries prepared in $output"
