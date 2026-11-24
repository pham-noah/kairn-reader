# Kairn 1.1.11

## A release about the bar staying where you put it

1.1.11 is four defects, all found on a phone running 1.1.1, all in the places a reader touches most: the
filter chips above the shelf, the two controls you use constantly, and the bar at the bottom of the page.

## The black mark beside the chips is gone

There was a dark rectangle sitting at the right edge of the collections row, over the ambient background. It
was not a shadow or a divider. It was the row's edge fade: a 28dp band painted in the app's own base colour,
`#0E1114`, laid over a backdrop that is not that colour but a wash of it. The band measured `(5,16,20)` against
a `(31,48,56)` backdrop - a black rectangle drawn on purpose beside a white chip.

The paint is gone, and the row's last chip is simply cut by the edge of the screen. That cut is the thing the
fade was hiding: a chip with no right edge says there is more to the side. A mask that faded the chips
themselves was tried first and taken back out, because it could not be checked before shipping - the test
renderer ignores the blend, so the band and the mask looked identical in every screenshot reviewed. Something
a screenshot cannot show is something that gets found on a phone.

## Want, Reading and Finished on one line

Three filter chips, each with a count, came to 390dp on a 411dp phone and dropped Finished onto a second row.
Squeezing each into a third of the width is worse: on a 360dp phone the labels ellipsised to "Readi... 1".

The row now measures its own labels in the styles it will draw them in, and picks the widest arrangement that
fits: all three with counts if they fit, the same three without counts if they do not, and the wrapping row
above 1.5x type, where "Finished" is larger than a third of any phone. At your phone's width you keep the
counts.

## Haptics, actually

The app asked the system for haptic feedback. That call is a request, not a vibration: the system is free to
drop it, and it does whenever touch feedback is turned down - which is how a reading app ends up with no
haptics at all on a device whose motor is perfectly good.

Kairn now drives the vibration motor itself: 12 milliseconds at 47% for a press, 20 at 74% when a switch lands,
32 at full strength when a press becomes a gesture, with the vibration permission declared and a device without an
amplitude control getting the motor's own default instead of nothing. The filter chips, which had none, tick too - they
change what is on the shelf, which is at least the event a button press is for.

## The seek bar holds still

Two separate faults in the same gesture.

Dragging the progress bar for more than about four seconds had the bar fold itself away **under your thumb** -
the auto-hide clock only counted taps and page turns, and a drag was not one. The bar slid out, the slider left
the screen, the gesture was cancelled, and the seek you had just made was thrown away. A seek now suspends the
fold entirely, and letting go restarts the clock.

And letting go used to snap the knob back. The bar fell back to the book's saved progress the instant the thumb
came up, and that number is only updated once the new position has actually loaded - so every seek put the knob
back where the drag started and then jumped it forward a beat later. The released value is held until the page
lands, and the chapter name, the percentage and the time left read it too.

Signed with the same CN=Kairn key as every release since 0.8.2, so it installs over 1.1.1 in place.
