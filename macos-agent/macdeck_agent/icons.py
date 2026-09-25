"""Key images: brand logos (logos/*.svg) and apps' own macOS icons, rendered to PNG.

Layouts refer to images by content hash ("sha1:…"); the server sends each image's bytes to a
phone only once per connection (see server.send_layout), instead of in every layout.
"""
import base64
import hashlib
from pathlib import Path

from AppKit import (
    NSBezierPath,
    NSBitmapImageFileTypePNG,
    NSBitmapImageRep,
    NSColor,
    NSColorSpace,
    NSCompositingOperationSourceOver,
    NSDeviceRGBColorSpace,
    NSGraphicsContext,
    NSImage,
    NSMakeRect,
    NSWorkspace,
    NSZeroRect,
)
from Foundation import NSFileManager

from . import config

ICON_SIZE = 96

# ref ("sha1:…") -> base64 PNG, for every image any layout has referred to.
IMAGES = {}
# ("app"|"logo", name) -> ref or None; rendering is slow, so each icon is drawn once.
_refs = {}


def app_path(ref):
    """Path of an installed app, by bundle ID ("com.spotify.client") or name ("Spotify")."""
    ws = NSWorkspace.sharedWorkspace()
    url = ws.URLForApplicationWithBundleIdentifier_(ref)
    return url.path() if url else ws.fullPathForApplication_(ref)


def app_display_name(ref):
    path = app_path(ref)
    if not path:
        return ref
    return NSFileManager.defaultManager().displayNameAtPath_(path).removesuffix(".app")


def render_png(image, tile=False, size=ICON_SIZE):
    """Draw an NSImage centered in a square (keeping its aspect ratio) and return a bitmap rep.

    tile=True puts it on a white rounded square, for logos too dark to see on the phone's keys.
    """
    rep = NSBitmapImageRep.alloc().initWithBitmapDataPlanes_pixelsWide_pixelsHigh_bitsPerSample_samplesPerPixel_hasAlpha_isPlanar_colorSpaceName_bytesPerRow_bitsPerPixel_(
        None, size, size, 8, 4, True, False, NSDeviceRGBColorSpace, 0, 0
    )
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.setCurrentContext_(NSGraphicsContext.graphicsContextWithBitmapImageRep_(rep))
    inset = 0
    if tile:
        NSColor.whiteColor().setFill()
        NSBezierPath.bezierPathWithRoundedRect_xRadius_yRadius_(
            NSMakeRect(0, 0, size, size), size * 0.22, size * 0.22
        ).fill()
        inset = size * 0.18
    box = size - 2 * inset
    w, h = image.size().width, image.size().height
    scale = box / max(w, h, 1)
    dw, dh = w * scale, h * scale
    image.drawInRect_fromRect_operation_fraction_(
        NSMakeRect((size - dw) / 2, (size - dh) / 2, dw, dh), NSZeroRect, NSCompositingOperationSourceOver, 1.0
    )
    NSGraphicsContext.restoreGraphicsState()
    return rep


def is_dark(rep, size=ICON_SIZE):
    """Whether the visible pixels are mostly near-black (e.g. OpenAI's logo)."""
    total = weight = 0.0
    for y in range(0, size, 4):
        for x in range(0, size, 4):
            c = rep.colorAtX_y_(x, y)
            if c is None or c.alphaComponent() < 0.5:
                continue
            c = c.colorUsingColorSpace_(NSColorSpace.genericRGBColorSpace()) or c
            total += 0.2126 * c.redComponent() + 0.7152 * c.greenComponent() + 0.0722 * c.blueComponent()
            weight += 1
    return weight > 0 and total / weight < 0.25


def register(png_bytes):
    """Remember an image and return its content-hash reference."""
    ref = "sha1:" + hashlib.sha1(png_bytes).hexdigest()
    IMAGES.setdefault(ref, base64.b64encode(png_bytes).decode())
    return ref


def _png(rep):
    return bytes(rep.representationUsingType_properties_(NSBitmapImageFileTypePNG, {}))


def app_icon_ref(ref):
    """The app's real macOS icon, or None if the app isn't installed."""
    key = ("app", ref)
    if key not in _refs:
        path = app_path(ref)
        _refs[key] = register(_png(render_png(NSWorkspace.sharedWorkspace().iconForFile_(path)))) if path else None
    return _refs[key]


def logo_ref(name):
    """A brand logo from logos/<name>.svg (CC0, github.com/gilbarbara/logos)."""
    key = ("logo", name)
    if key not in _refs:
        path = config.LOGOS_DIR / f"{Path(name).name}.svg"   # no path tricks: logos/ only
        image = NSImage.alloc().initWithContentsOfFile_(str(path)) if path.is_file() else None
        if image is None:
            print(f"Logo not found: {path.name} (see {config.LOGOS_DIR.name}/)")
            _refs[key] = None
        else:
            rep = render_png(image)
            _refs[key] = register(_png(render_png(image, tile=True) if is_dark(rep) else rep))
    return _refs[key]


def icon_ref(icon):
    """Image reference for "app:<bundle id or name>" / "logo:<name>" icons, else None."""
    if icon.startswith("app:"):
        return app_icon_ref(icon[4:])
    if icon.startswith("logo:"):
        return logo_ref(icon[5:])
    return None
