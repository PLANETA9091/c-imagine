"""Where a key sits on the /keyboard HUD, and the look that puts the HUD pointer on it.

Mirrors paper-plugin/src/main/java/dev/cim/hud/KeyboardLayout.java (keys page) and KeyboardState.LAYOUT
(key codes in order). The pointer is relative: VirtualKeyboard starts it at the centre when the keyboard
opens (at the view yaw/pitch of that moment) and each draw moves it by the look delta times
ScreenHud.Pointer.SENSITIVITY (6 GUI px per degree; pitch down is +y). A click is a swing or a use
packet, so the runner aims with horizon-ctl look and clicks with mouse left.

Pure Python, stdlib only. test_keyboard_layout.py checks this table against the Java source when present.
"""

# KeyboardState.LAYOUT key codes, row-major, COLUMNS per row (the Java order; CELL_HOLD -2, CELL_PAGE -3)
COLUMNS = 15
LAYOUT_KEYS = [
    256, 290, 291, 292, 293, 294, 295, 296, 297, 298, 299, 300, 301, 260, 261,
    96, 49, 50, 51, 52, 53, 54, 55, 56, 57, 48, 45, 61, 259, 268,
    258, 81, 87, 69, 82, 84, 89, 85, 73, 79, 80, 91, 93, 92, 269,
    280, 65, 83, 68, 70, 71, 72, 74, 75, 76, 59, 39, 257, 266, 267,
    340, 90, 88, 67, 86, 66, 78, 77, 44, 46, 47, 344, 265, -2, -3,
    341, 342, 32, 346, 345, 263, 264, 262, 283, 284, 281, 282, 332, 333, 334,
]

CELL_HOLD = -2
CELL_PAGE = -3
KEY_W = 25
CAP_H = 14
PITCH_X = KEY_W + 1
PITCH_Y = CAP_H + 1
LEFT = -(COLUMNS * PITCH_X) // 2   # Java int division of a negative product: -390 / 2 = -195
TOP = -8
ROWS_Y = TOP + PITCH_Y + 1
SENSITIVITY = 6.0  # ScreenHud.Pointer.SENSITIVITY (GUI px per degree)


def key_cap(key):
    """(x, y) of the keys-page cap for GLFW key code `key`, relative to the screen centre (the Java
    KeyboardLayout coordinates). Raises ValueError for a key with no cap on the keys page."""
    try:
        i = LAYOUT_KEYS.index(key)
    except ValueError:
        raise ValueError("no keycap for key code {!r} on the /keyboard keys page".format(key))
    return LEFT + (i % COLUMNS) * PITCH_X, ROWS_Y + (i // COLUMNS) * PITCH_Y


def cap_aim_point(key):
    """The GUI point the pointer should sit on to press the cap: centre x, y + 7 (CAP_H / 2), as
    VirtualKeyboard.aim does."""
    x, y = key_cap(key)
    return x + KEY_W // 2, y + CAP_H // 2


def aim_deltas(px, py):
    """Look change (yaw delta, pitch delta) in degrees that moves the pointer from the centre to (px, py)."""
    return px / SENSITIVITY, py / SENSITIVITY
