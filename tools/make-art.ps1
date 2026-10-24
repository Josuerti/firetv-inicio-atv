# Genera icon.png (192px) y banner.png (640x360) del launcher en res/drawable-xhdpi
Add-Type -AssemblyName System.Drawing
$out = Join-Path $PSScriptRoot "..\launcher\res\drawable-xhdpi"
New-Item -ItemType Directory -Force $out | Out-Null

function New-Art($w, $h, $text, $size, $path) {
    $bmp = New-Object System.Drawing.Bitmap $w, $h
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'; $g.TextRenderingHint = 'AntiAliasGridFit'
    $rect = New-Object System.Drawing.Rectangle 0, 0, $w, $h
    $bg = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, ([System.Drawing.Color]::FromArgb(255, 44, 44, 48)), ([System.Drawing.Color]::FromArgb(255, 0, 0, 0)), 60
    $g.FillRectangle($bg, $rect)
    $font = New-Object System.Drawing.Font "Segoe UI Light", $size, ([System.Drawing.FontStyle]::Regular), ([System.Drawing.GraphicsUnit]::Pixel)
    $sf = New-Object System.Drawing.StringFormat
    $sf.Alignment = 'Center'; $sf.LineAlignment = 'Center'
    $g.DrawString($text, $font, [System.Drawing.Brushes]::White, (New-Object System.Drawing.RectangleF 0, 0, $w, $h), $sf)
    $g.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

New-Art 192 192 "tv" 110 (Join-Path $out "icon.png")
New-Art 640 360 "Inicio" 120 (Join-Path $out "banner.png")
Get-ChildItem $out | Select-Object Name, Length
