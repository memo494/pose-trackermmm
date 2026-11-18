# Test checklist (for the owner, on the phone)

How to use this file: do the steps in order, one block at a time. Every step has a **Pass** line (what you
should see) and a **If it fails** line. Whenever something fails, do the same thing: open Pose Tracker, press
**Share diagnostics**, send the zip to the agent, and add one sentence about what was on the screen. You do
not need to understand the zip; it holds the app log, the phone model, the settings and the last numbers
the app measured. It contains no passwords and no photos.

---

## M0b — first dots over other apps

### 0. Install the new APK
1. On the phone, open the GitHub release named **`latest`** of the project and download the `.apk` file.
   (Do this only after the pull request is merged; the file is replaced on every merge.)
2. Open the downloaded file and install it **over the old one** (do not uninstall first).
3. Open **Pose Tracker**. At the top of the screen you see a small line like `v0.1.57 · a1b2c3d`
   (version and build id).

**Pass:** the app opens and shows that line, and the status says **Stopped**.
**If it fails:** the phone refuses to install or the app closes at once: tell the agent the exact message you
saw. If the app opens afterwards, press **Share diagnostics**.

### 1. First start and permissions
1. Press **Start**. The app asks you to allow **display over other apps**: press **Allow display over other
   apps**, switch it on for Pose Tracker, and go back with the Back button.
2. Press **Start** again. Allow **notifications** when the phone asks.
3. The phone asks to record or cast your screen. Choose **Entire screen** (not a single app) and confirm.

**Pass:** the status says **Running: tracking people on your screen**, a notification "Pose Tracker is
running" is shown, and a small text line appears in a screen corner.
**If it fails:** the app says it could not start, or nothing happens after step 3: Share diagnostics.

### 2. Dots on people
1. Press Home and open the **Tuya camera app** with one or more people visible.
2. Look at the people on the screen.

**Pass:** a thin **green ring** sits on each person's head and follows them when they move. The text line in
the corner reads like `v0.1.57 · a1b2c3d` and below it `GPU 12.3 ms 58 fps 610x1356 ts=img`:
- the build id matches the one in the app;
- it says `GPU` (or `CPU`, see block 7), a time in `ms`, a number of `fps`, a picture size, and `ts=img`.

**If it fails:** no rings at all, rings on the wrong place, or `--` everywhere for more than a few seconds:
Share diagnostics, and say what the camera showed (people, how many, indoors or outdoors).

### 3. Touches still reach the app below
1. With the rings showing, tap and swipe in the camera app (open a menu, change the camera, scroll).

**Pass:** the camera app reacts exactly as it does without Pose Tracker. Nothing feels blocked.
**If it fails:** taps do nothing or only work sometimes: Share diagnostics and tell which app and which tap.

### 4. Still picture means almost no frames
1. Pause the video (or point the camera at a still scene so nothing on the screen moves) and wait 5 seconds.

**Pass:** the `fps` number in the corner falls to **0** within about two seconds and stays there. When the
picture moves again, it rises. With people in a still picture a small value can remain; that is not a pass
either, so report it too (the agent needs to know).
**If it fails:** the number keeps jumping (for example 1, 2, 5…) or stays above 0 on a still picture:
Share diagnostics.

### 5. Rotate to landscape
1. Turn the phone sideways (landscape) and wait up to 1 second. Turn it back.

**Pass:** within about a second the rings are on the people again in both directions. For a moment the
corner text may say "Capture not display-sized: dots hidden"; it must disappear by itself.
**If it fails:** rings stay in the wrong place, or the warning stays for more than a few seconds: Share
diagnostics (right after it happens) and say which way you turned.

### 6. Lock the screen
1. With tracking running, press the power button to lock the phone. Wait 5 seconds and unlock it.
2. Look at the Pose Tracker notification and open the app.

**Pass:** either of these is fine. (a) Tracking has stopped cleanly: status says **Stopped**, no
notification, no corner text, and pressing **Start** again asks for the screen-recording permission again
(that is expected). (b) Tracking simply continues after you unlock (some phones keep the screen recording
alive).
**If it fails:** the app says **Running** but there is no corner text, or the phone shows an error: Share
diagnostics.

### 7. Warnings on the screen
- **Red "Running on CPU" banner:** the model runs on the slow path. Not a phone problem and nothing you
  can fix. Tell the agent (it needs a change in the model), and send the zip.
- **"Model not loaded: no detection":** Share diagnostics.
- **"Capture not display-sized: dots hidden"** that does not go away: Share diagnostics.
- **"Content not visible: dots hidden":** the phone says the captured content is not visible (for example
  while the screen is locked or an app hides itself). Only report it if it stays on a normal screen.

### 8. Stopping
1. With tracking running, pull down the notification shade and press **Stop** in the Pose Tracker
   notification.
2. Start again, then open the app and press **Stop** inside the app.

**Pass:** both ways stop it at once: the rings, the corner text and the notification disappear and the app
says **Stopped**.
**If it fails:** either Stop does nothing: Share diagnostics.

### 9. Permissions you say "no" to
Try each one on purpose; the app must never get stuck.
1. In Settings, switch off "display over other apps" for Pose Tracker, press **Start**.
   **Pass:** a message asks you to allow it first; **Allow display over other apps** opens the setting.
2. Press **Start** and answer **Don't allow** to notifications.
   **Pass:** the start goes on to the screen-recording question anyway (the notification may be hidden, that is
   fine), and tracking still works.
3. Press **Start** and **Cancel** the screen-recording permission.
   **Pass:** a message says screen capture was not allowed; **Start** works again.

**If it fails:** the app freezes, shows no message, or the Start button stays grey: Share diagnostics.

### 10. Protected video is black (expected)
Apps with protected video (for example some streaming apps) show black to Pose Tracker. No rings there is
**expected and not a bug**. Do not report it.

### 11. The report button itself
1. In Pose Tracker press **Share diagnostics**. The share sheet opens; choose how to send it (mail,
   messenger, Drive).
2. Press **Show log** to see the last log lines; **Refresh** updates them; **Hide log** closes it.
3. If the app ever closes by itself, open it again: it says "The app stopped unexpectedly last time" with
   **Share diagnostics** and **Dismiss**.

**Pass:** a file named `pose-tracker-diagnostics.zip` is offered for sending.
**If it fails:** the app says it could not prepare the diagnostics: take a screenshot of the log screen and
send that.

---

## M0c — import a model, steadier marks

Do M0b first (the app must already work). Blocks 1 to 7 use the same video each time: pick one video with a
person at medium distance (not close, not tiny) and play it from the same point every time.

### 1. Get the 640 model file
1. On the phone, open the project's **Releases** page on GitHub and open the release named **Models** (tag
   `models`).
2. Download the file **`yolo26n-pose_640.tflite`** (about 12 MB). It is not an app: do not "open" it.

**Pass:** the file is in the phone's Downloads folder and is about 12 MB.
**If it fails:** the release or the file is missing: tell the agent what the Releases page shows.

### 2. Import it
1. Open Pose Tracker (status **Stopped**). In the block **Model** the line says **Built-in (416)**.
2. Press **Import model** and pick `yolo26n-pose_640.tflite` in the file picker.
3. Wait a few seconds ("Importing: copying and checking the model.").

**Pass:** a line says **Imported yolo26n-pose_640.tflite. It is used the next time you press Start.** and the
model line says **yolo26n-pose_640.tflite (640, imported)**.
**If it fails:** any other text under **Model**: Share diagnostics and send a screenshot of that block.

### 3. Run the imported model
1. Press **Start** (allow the screen recording again) and play the video.
2. In the corner text, read the line that starts with `det`, for example `det 1/1 trk 1 max 0.84 in 640 imp`, and
   the line above it (`GPU 51.0 ms 19 fps ...`). Write down `ms`, `fps` and the `max` number for the same
   person.

**Pass:** the `det` line ends with **`in 640 imp`**, there is no warning line "Imported model not used: built-in
model", and rings appear on the people. (`ms` is expected to be larger than with the built-in model, roughly 45
to 55 ms and 17 to 20 fps; these are estimates, the numbers you write down are the real answer.)
**If it fails:** the line says `in 416` or the warning "Imported model not used: built-in model" shows: Share
diagnostics (it names the reason).

### 4. Compare with the built-in model on the same video
1. Stop. Press **Use built-in model** and check that the model line says **Built-in (416)**.
2. Start, play the same video from the same point, write down `ms`, `fps` and the `max` number for the same
   person as in block 3.
3. Send both sets of numbers (built-in and imported) in one message.

**Pass:** with the imported model the `max` number for the medium-distance person is **clearly higher** than
with the built-in one, and the ring stays on the person more steadily. The `det` line says `in 416` (no `imp`)
again, and the speed is back to what you saw before.
**If it fails:** the numbers are not better, or still 0.00 for a person who is clearly visible: that is a
result, not a failure of the app: send both sets of numbers and one screenshot of each.

### 5. A file that is not a model
1. Stop. Press **Import model** and pick a **photo** (or any file that is not a model).

**Pass:** the app refuses it with a reason: either **This file is too small to be a pose model.** or **This is not
a TensorFlow Lite model file (.tflite).** counts as a pass (a very small file gets the first text, a bigger
one the second), and the model line still shows the model you had before.
**If it fails:** the app accepts the photo, closes, or the model line changes: Share diagnostics.

### 6. Steadier marks (debug switch)
1. In the app find the switch **Debug marks (box and body points)**. It should be **off**. Stop, Start, play
   the video with the person at medium distance.
2. Look at the marks on each person.
3. Switch it **on**, press **Stop** and **Start** again (it is used from the next Start), play the same video.

**Pass:** (a) switch off: each person has only a **ring on the head and short corner marks** (no full box, no
dots on the body), and the person at medium distance **blinks clearly less than in v0.1.37**. (b) switch on: the
full box and the dots on the body are back.
**If it fails:** the marks are still a full box with the switch off, the switch does not change anything after
Stop and Start, or the person still blinks as before: Share diagnostics and say which of (a) or (b) failed.

### 7. Back to the built-in model, and the report
1. Stop. Import the 640 file again, Start, then Stop and press **Use built-in model**, Start.
2. Press **Share diagnostics** and open `summary.txt` in the zip (or send it to the agent).

**Pass:** after **Use built-in model** the model line says **Built-in (416)** and the `det` line says `in 416`.
`summary.txt` names the model: `modelSource`, `modelFile`, `modelSha256`, `modelInputSize` (416 for the built-in,
640 for the imported run). Share a second zip after an imported run to see `modelSource=imported`.
**If it fails:** the model fields are missing from `summary.txt`: tell the agent.

---

## M1a — rectangular models and timing numbers

Do M0c first (the app must already work and you must know how to import a model). Blocks 2 and 3 use the same
video each time: pick one video with people at medium distance, hold the phone **sideways (landscape)**, and
play it from the same point every time.

### 1. The stored 640 model still works
1. Install the newest build from the `latest` release. If you still have the 640 model imported, do not import it
   again.
2. Press **Start** (allow the screen recording again) and look at the line that starts with `det`.

**Pass:** the `det` line ends with **`in 640 imp`** and rings appear on the people.
**If it fails:** the model line says **Built-in (416)** or the `det` line shows a warning: Share diagnostics.

### 2. Get and import a wide model
1. On the phone, open the project's **Releases** page on GitHub, open the release named **Models** (tag `models`)
   and download **`yolo26n-pose_832x384.tflite`** (about 12 MB). Try this one first. The release also has
   `yolo26n-pose_640x288.tflite` and `yolo26n-pose_960x448.tflite` for block 3.
2. Open Pose Tracker (status **Stopped**), press **Import model** and pick that file.
3. Press **Start**, turn the phone to landscape and play the video.

**Pass:** the line says **Imported yolo26n-pose_832x384.tflite**, the `det` line ends with **`in 832x384 imp`**,
the line above it shows **GPU**, and there is no red banner.
**If it fails:** the import is refused, the `det` line still says `in 640 imp` or `in 416`, or the line above says
**CPU**: Share diagnostics and write down the file name you imported.

A wide model is for landscape. Held upright (portrait) people are **smaller** for it than with 640. That is
expected, not a fault: in portrait switch back to the 640 model or **Use built-in model**.

### 3. Compare the models on the same video
1. Landscape, same video, same starting point. For each model below, press **Stop**, import it, **Start**, play
   the video, and write down three numbers from the corner text: **`ms`** and **`fps`** (the line above `det`)
   and **`max`** (the `det` line) for the same person.
2. Do this for **640** (the one you had) and **832x384**, and if you like also **960x448** and **640x288**.
3. Send all the sets of numbers in one message (model, `ms`, `fps`, `max`).

**Pass:** you have `ms`, `fps` and `max` for at least 640 and 832x384. (Estimates only: 640x288 and 832x384 should
be faster than 640 and 960x448 about as fast; the numbers you write down are the real answer.)
**If it fails:** a model runs on **CPU** or shows a red banner: that is a result, write down which size and send
Share diagnostics.

### 4. The timing numbers in the report
1. After a run of at least a minute, press **Share diagnostics** and open `summary.txt` in the zip (or send it to
   the agent).

**Pass:** `summary.txt` has the lines `timingSamples`, `copyMsP50` / `copyMsP95`, `preMs…`, `inferMs…`, `postMs…`,
`frameMs…`, `resultAgeMs…` (each with `P50` and `P95`) and `thermalStatus` and `thermalHeadroom`, with numbers
in them (`n/a` is allowed only where the phone cannot tell, for example `thermalHeadroom`). `modelInputSize=` says
`832x384` for the wide model. The corner text looks the same as before.
**If it fails:** the lines are missing, or everything says `n/a` after a minute of running: tell the agent.

---

## M3 — marks from tracks

Do M0c first (the app must already work). This section is about what the marks do over time: the rings and
corner marks now come from the tracker, which keeps one number per person. Blocks 1 to 4 and 6 use one video
with a few people walking (two of them should pass each other once); play it from the same point every time.

### 1. Rings and brackets from tracks
1. Install the newest build from the `latest` release. Press **Start** (allow the screen recording again) and
   play the video.
2. Look at the people and at the line that starts with `det`, for example `det 2/3 trk 2 max 0.89 in 640 imp`.

**Pass:** a **ring** sits on each person's head and **short corner marks** sit on each person; the `det` line has
a `trk` number (the number of people the app is tracking, for example `trk 2`) that matches the people with marks.
A person's marks appear a moment (about a tenth of a second) after the person does, not at the first frame.
**If it fails:** no `trk` in the `det` line, no marks although people are visible, or the marks sit far from the
heads: Share diagnostics and say how many people were on the screen.

### 2. A short miss does not blink
1. In the same video, watch a person the model loses for a moment (partly hidden, far away, or turning).

**Pass:** the ring stays with the person and does not blink off and on; if the
person is gone for good it disappears after under one second (up to 0.8 s).
**If it fails:** the ring blinks off for a fraction of a second although the person is still there, or it stays
long after the person left: Share diagnostics and say what the person did.

### 3. Two people keep their own marks
1. Switch **Debug marks (box and body points)** **on**, press **Stop** and **Start** again (it is used from the next
   Start). Play the part of the video where two people walk past each other.
2. Look at the small number above each person's marks and at the full boxes (those come from the raw detections).

**Pass:** each person keeps **the same number** while they cross and after; the numbers are not swapped. (With the
switch on you also see the full box and the dots; the ring and corner marks are still from the tracker.)
**If it fails:** the numbers swap or a person gets a new number for no reason: Share diagnostics, say at which
second of the video, and which two people. A slow crossing in one row can swap numbers; say if it did.

### 4. Pause and resume
1. With the video playing and rings showing, **pause** it. Wait 5 seconds. Then resume.

**Pass:** while paused the rings **stay** where they were and the first line of the corner text shows **0 fps**;
after resume the **same** rings (and, with Debug marks on, the same numbers) go on with the people.
**If it fails:** the rings vanish on pause, the fps stays high on a still picture, or the numbers restart after
resume: Share diagnostics.

### 5. A still picture
1. Open a **photo with a person** (or a paused frame opened from elsewhere) so that nothing on the screen
   moves, and wait.

**Pass:** the ring and corner marks appear **within about a second** (0.4 to 0.9 s) after the picture is on the
screen, and the `det` line says `trk 1` (or the number of people). Before this version they came at once.
**If it fails:** no marks after 3 seconds on a picture where the model finds the person (`det 1/1` or more):
Share diagnostics.

### 6. Rotate the phone
1. With the video playing and rings showing, turn the phone to the other orientation (portrait to landscape or
   back).

**Pass:** for a moment there are no marks, then the rings and corner marks come back in the **new** orientation,
on the heads. The numbers (Debug marks on) go on counting and are **higher** than before.
**If it fails:** the rings come back in the wrong place (as if the phone were still turned), or never come back:
Share diagnostics and say which way you turned.

### 7. The tracker lines in the report
1. After a run of a minute or more with people, press **Share diagnostics** and open `summary.txt` in the zip
   (or send it to the agent).

**Pass:** `summary.txt` has the lines `tracksShown`, `tracksMostAtOnce`, `trackBirths`, `trackConfirmations`,
`trackIdlePromotions`, `trackRecoveries` and `trackRemovals`, with numbers (not `n/a`); `tracksMostAtOnce` is at
least the number of people you saw together.
**If it fails:** the lines are missing, or all of them are 0 after a run with people: tell the agent.

---

## M4a — rings sooner, steadier marks

Do M3 first (the app must already work). This section is about two changes: a person gets the ring **sooner**
(a single missed frame no longer restarts the wait) and the ring and corner marks are **smoothed** so they do not
tremble. Use the **same video as in M3**, played from the same point, and the same settings.

### 1. Rings come sooner
1. Install the newest build from the `latest` release. Press **Start** (allow the screen recording again) and
   play the M3 video. Watch the people whose ring came late in M3.

**Pass:** those people get their ring **sooner**, about half a second at most for most of them.
**If it fails:** the same people still wait as long as in M3, or rings appear on things that are not people:
Share diagnostics and say which person and at which second of the video.

### 2. A person standing still
1. Watch a person who stands (or sits) still for a few seconds, ring on the head.

**Pass:** the ring does **not tremble**; it stays calm on the head.
**If it fails:** the ring still shakes by a few pixels, or it drifts away from the head: Share diagnostics and say
how far away the person was.

### 3. A walking person
1. Watch a person walk across the picture, and then a person who stops suddenly.

**Pass:** the ring **follows without visible delay** and the corner marks stay around the person together with the
ring; after a sudden stop the ring **does not shoot past** the head.
**If it fails:** the ring clearly trails behind the head, runs ahead of it after a stop, or the ring and the
corner marks move apart: Share diagnostics and say which of the three it was.

### 4. The numbers in the report
1. After the same run, press **Share diagnostics** and open `summary.txt` (or send the zip to the agent).
2. Divide `trackConfirmations` by `trackBirths`.

**Pass:** the share of confirmations is **higher than in M3** (in M3 the run had 103 confirmations for 278 births,
about 0.37); the other tracker lines are still numbers, not `n/a`.
**If it fails:** the share is the same or lower, or the lines are missing: send the zip and both numbers.

---

## M4b — fading marks and tap-to-lock

Do M4a first. This section is about two changes: a ring of a person the model loses **fades** instead of
staying full and then vanishing, and you can **lock** one person with the small round handle at the right edge of
the screen. The handle is part of the picture the app sees; it is small on purpose.

### 1. A lost ring fades
1. Install the newest build from the `latest` release. Press **Start** and play the M3 video. Watch a person
   whom the model loses for a moment (someone who is partly hidden or turns away).

**Pass:** the ring and the corner marks of that person **fade out over about 0.8 seconds** instead
of staying full and then disappearing; if the person comes back the marks are full again.
**If it fails:** the marks still stay full and then vanish, or they flicker: Share diagnostics and say which person.

### 2. Lock a person
1. Tap the small round handle at the **right edge** of the screen. The corner text shows **tap a person** and the
   handle gets a dot.
2. Tap a person (anywhere near the head or the body).
3. Watch that person walk.

**Pass:** that person gets a **second, thicker ring** and keeps it while walking; the other people keep their
ordinary rings. Tap the handle again: the double ring is **gone**.
**If it fails:** nothing happens on the first tap, the wrong person gets the ring, or the ring moves to another
person: Share diagnostics and say what you tapped.

### 3. A tap on an empty spot, and the timeout
1. Tap the handle, then tap an empty spot far from every person. Nothing must be locked.
2. Tap the handle again and wait about **five seconds** without tapping.

**Pass:** the empty tap locks **nobody**, and the **tap a person** text goes away by itself after about five seconds.
Tapping the handle a second time while the text is shown also cancels it.
**If it fails:** somebody gets locked by the empty tap, or the text stays: Share diagnostics.

### 4. Taps reach the app below again
1. While **not** armed, tap and scroll in the app under the overlay (for example the video player).
2. Arm (tap the handle), then tap a person. Now tap and scroll the app below again.
3. Arm again and press **Stop** in the notification; then **Start** and try the app below.
4. Arm, then rotate the phone, and try the app below.

**Pass:** in every case taps and scrolling **reach the app below** normally; after Stop or a rotation the lock is
gone and nothing stays armed.
**If it fails:** after arming the app below does not react to taps (the overlay stays touchable): Share
diagnostics and say what you did before.

## M4c — the lock comes back to the same person

Do M4b first. This section is about one change: after you lock a person and they **leave the picture and come
back**, the double ring returns to them by itself, recognised by the colours of their clothes (the upper body). It
does not use their face and keeps no picture, only a colour count in memory, dropped when the lock ends. For this
section play a video (or use the camera app) where one person walks out of the picture and comes back, and where
people wear **clearly different** clothes.

### 1. Out and back
1. Press **Start**, tap the handle, tap one person: they get the double ring.
2. Let that person walk **out of the picture** (or be hidden for several seconds) and then **come back within a
   minute**.

**Pass:** while they are away the **double ring is gone** (no ring on anybody); when they are back and have an
ordinary ring, the **double ring returns on them** within about a second.
**If it fails:** the ring never comes back, or it comes back on somebody else: Share diagnostics right away (the
`lock:` lines in the log show why) and say what the person wore.

### 2. Somebody else comes
1. Lock a person, let them leave. Now let **someone in clearly different clothes** (another colour) walk in.

**Pass:** **no double ring** jumps to the newcomer.
**If it fails:** the newcomer gets the double ring: Share diagnostics.

### 3. Two people in similar clothes
1. Lock a person who wears, say, a black shirt. Let them leave and let **two people in black shirts** come in.

**Pass:** the double ring **may stay away** (that is by design: two similar people are not told apart), but it must
**not go to the wrong one**.
**If it fails:** the ring goes to a person who was not the locked one: Share diagnostics and say how similar the
clothes were.

### 4. The wait ends
1. Lock a person and let them leave. Tap the handle once (it counts as locked while the app waits): the wait is
   over. Tap it again and tap a person to lock somebody new.
2. Lock a person, let them leave, and **wait more than one minute** (keep the video playing). Then let them come back.

**Pass:** after the handle tap, a returning person gets **no** ring until you lock again; after more than a minute the
ring does **not** come back either (`lock: expired` in the log). Pressing **Stop** or rotating the phone also ends the
wait.
**If it fails:** a ring appears out of nowhere after the handle tap, Stop or a rotation: Share diagnostics.

### 5. The numbers for tuning
1. After blocks 1 to 3, press **Save diagnostics** (or Share).

**Pass:** the log has the lines `lock: set`, `lock: pending`, `lock: relocked id=… d=… next=…` and, for people who were
not accepted, `lock: candidate id=… d=… rejected (…)`; `summary.txt` has `locks`, `lockRelocks`, `lockRelockRejects` and
`lockExpired`. Send the zip even when everything passed: the `d=` and `next=` numbers tune the colour limits.

## M4e — why a person gets a new id (numbers only, nothing changes on the screen)

Nothing looks different in this build. It writes down **why** the app gave a person a new id when the old ring was
still fading next to them, so the next change can fix the right thing. Use a video or the camera app with **one or
two people** for about **three minutes**.

### 1. Walk, turn, sit, with a lock on
1. Press **Start**, tap the handle and tap one person: they get the double ring.
2. Let that person **walk across the picture**, then **turn around** (or walk back), then **sit down or stand still**
   for a while, then walk again. Keep it going for about three minutes.
3. Press **Save diagnostics** (or Share) right after, even if the ring never jumped.

**Pass:** the report is saved. (There is no pass or fail for the ids here.) The log has lines like
`track: miss id=3 -> new id=4 lostMs=100 score=0.81 stage=high gateChi2=35.20 iou=0.00 headRatio=0.90 hRatio=1.05
cost=0.95 reason=gate`, at most 30 a minute; `summary.txt` has `assocMisses`, `assocMissGate`, `assocMissCost`,
`assocMissTaken` and `assocMissLowBirth`.
**If it fails:** the app crashes or the lines never appear while the ring changed ids: Share diagnostics and say so.

Send the zip also when the ring stayed on the person the whole time.

---

---

## What to send back
1. The **diagnostics zip** (press **Share diagnostics** or **Save diagnostics** right after the problem, not hours
   later; a saved zip can be sent later).
2. **One sentence** about what was on the screen (which app, how many people, what you did).
3. The **block number** of this checklist that failed (for example "block 5"; for the M0c section say "M0c block 5",
   for M1a "M1a block 3", for M3 "M3 block 3", for M4a "M4a block 2", for M4b "M4b block 2", for M4c "M4c block 2").
4. A **screenshot** if the corner text or a warning looked strange.
5. If everything passed: say "all blocks passed" and the build id from the top of the app.

## For the agent (not for the owner)
These checks need a cable and `adb`, or the benchmark screen of M1b, and are not part of the steps above:
- logcat must not show "Untrusted touch due to occlusion" while tapping through the overlay;
- `now - image.timestamp` must be within 0 to 30 ms (the `ts=` value in the zip summary tells whether the
  fallback was used: `arrivalFallbacks` should stay near 0);
- dot A/B comparison: head shift under 0.03 of the person height, keypoint 0 to 4 confidence drop under 0.05.
  On the phone the A/B is the switch **Debug marks (box and body points)**: run the same video once with it off
  (ring and corner marks) and once with it on (box and body points), and compare the `max` number of the `det`
  line and how often the marks blink (M0c block 6). Offline, the dots and the box already lowered the next
  frame's score (`docs/plans/M0c.md`, "Measured after T1").
- `summary.txt` timing fields (M1a), in this order after the model fields: `timingSamples`, then `copyMsP50`,
  `copyMsP95`, `preMsP50`, `preMsP95`, `inferMsP50`, `inferMsP95`, `postMsP50`, `postMsP95`, `frameMsP50`,
  `frameMsP95`, `resultAgeMsP50`, `resultAgeMsP95`, `thermalStatus`, `thermalHeadroom`. The percentiles cover the
  newest `timingWindowFrames` (512) processed frames. `n/a` = no sample or not readable, never a guess. Compare
  `inferMsP50` with the HUD `ms` of the same run; `frameMsP95` and `resultAgeMsP95` show how bad the slow frames
  and the result delay are. `modelInputSize` is `640`, `832x384` or `0` (no model).
- `summary.txt` tracker fields (M3b), after `headYImg` and before the model fields: `tracksShown` (now),
  `tracksMostAtOnce`, `trackBirths`, `trackConfirmations`, `trackIdlePromotions`, `trackRecoveries`,
  `trackRemovals` (cumulative since Start; 0 before the first frame). `trackIdlePromotions` counts people who got
  their marks from the HUD tick on a still picture (M3 block 5); `trackRecoveries` counts lost tracks that were
  matched again (M3 block 2). Expected rate of marks on a still picture: 0.4 to 0.9 s after the last frame.
- `summary.txt` lock fields (M4c), after `trackRemovals` and before the model fields: `locks` (locks set), `lockRelocks`
  (a pending lock moved to a returning person), `lockRelockRejects` (candidates whose 10-frame judge window ended
  without acceptance), `lockExpired` (memory ran out), cumulative since Start. The log lines are `lock: set id=N`,
  `pending id=N frames=K` (K = frames that filled the signature), `relocked id=N -> M d=… next=…`, `candidate id=M d=… rejected (too far | ambiguous, next=… | no
  torso)`, `expired` and `cleared (handle | reset | stop | replaced | no signature)`. Read `d` (distance of the
  returning person to the remembered colours; smaller is closer, accepted up to `relockMaxDistance` 0.40) and `next`
  (distance of the nearest other person; `none` = nobody else, `unsampled` = somebody could not be sampled) to tune
  `relockMaxDistance` and `relockMinMargin`.
- `summary.txt` association-miss fields (M4e), after `lockExpired` and before the model fields: `assocMisses` (tracks
  born next to a LOST one), `assocMissGate` (value above `trackGateChi2`), `assocMissCost` (cost at or above the
  stage limit), `assocMissTaken` (the matcher would have taken the pair: should stay 0), `assocMissLowBirth`
  (unmatched detections below `trackBirthScore` next to a LOST track, once per frame). The log line is
  `track: miss id=L -> new id=N lostMs=… score=… stage=… gateChi2=… iou=… headRatio=… hRatio=… cost=… reason=…`
  (at most `assocMissLogPerMin` 30 a minute). Read `reason` first, then which number is far from its limit.
