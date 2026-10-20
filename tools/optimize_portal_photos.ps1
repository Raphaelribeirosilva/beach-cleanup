$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$imageRoot = Join-Path $PSScriptRoot '../src/main/resources/static/images'
$outputRoot = Join-Path $imageRoot 'web'
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
$codec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object MimeType -eq 'image/jpeg'
# Originals remain untouched. Resize only: the browser chooses a responsive crop.
foreach ($name in @('praia-de-camburi')) {
    $source = [System.Drawing.Image]::FromFile((Join-Path $imageRoot "$name.jpg"), $true)
    try {
        foreach ($width in @(480, 960, 1440)) {
            $height = [int][Math]::Round($source.Height * $width / $source.Width)
            $bitmap = New-Object System.Drawing.Bitmap($width, $height)
            $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
            $parameters = New-Object System.Drawing.Imaging.EncoderParameters(1)
            try {
                $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
                $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
                $graphics.DrawImage($source, 0, 0, $width, $height)
                # Keep available attribution fields; camera/profile metadata stays in the original.
                foreach ($property in $source.PropertyItems | Where-Object Id -in @(270,315,33432,33723,700)) {
                    $bitmap.SetPropertyItem($property)
                }
                $parameters.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter([System.Drawing.Imaging.Encoder]::Quality, [long]80)
                $output = Join-Path $outputRoot "$name-$width.jpg"
                $bitmap.Save($output, $codec, $parameters)
                Get-Item $output | Select-Object Name,Length
            } finally { $parameters.Dispose(); $graphics.Dispose(); $bitmap.Dispose() }
        }
    } finally { $source.Dispose() }
}
