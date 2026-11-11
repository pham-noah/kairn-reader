# Kairn 0.9.4

Motion, kept small. Nothing animates for longer than 170 ms, nothing moves more than a tenth of the screen, and
no animation changes a layout - so a tap never waits on it and nothing shifts under the finger.

## Press

Every button sinks 4% under the finger and comes back: 90 ms down, 90 ms up. That covers `KButton`,
`KIconButton`, the reader's mode pills and the four tool buttons, because they all go through one modifier
(`pressFeedback`) instead of each drawing its own. It is a scale, not a shadow or a size change: a scale cannot
reflow the row the button sits in.

The ripple is still there - the press scale is added to it, not instead of it.

## Tabs

- **Changing tab** slides and fades: a tenth of the width, 170 ms, direction taken from the tab order, so going
  left and going right look different. The outgoing screen fades in 120 ms, the incoming one in 140.
- **Swiping the content** changes tab as well: a horizontal flick past 110 dp moves one tab in the direction of
  the flick. It gives way to anything that scrolls sideways - the "Continue reading" shelf consumes the drag
  first, which cancels this detector - so the shelf keeps its horizontal scroll and only a deliberate full-width
  swipe on a non-scrolling area changes tab.
- **The nav bar's selection** fades its pill and its icon and label tint together over 160 ms, instead of
  snapping the mint on. One movement, not a colour swap.

## What is deliberately not animated

The library list, the settings rows and the sheets. They are read, not watched, and the sheets already slide in.
The reader's page turn is untouched.

## Verification

138 JVM and 59 on-device tests still pass unchanged - the motion is in the shell and the kit, so no screen
behaviour moved. lint 0 errors. Signed with the same CN=Kairn key, installs over 0.9.3 in place.

Compose animations follow the system "remove animations" setting through `MotionDurationScale`, so turning
animations off in Android settings leaves the app instant rather than broken.
