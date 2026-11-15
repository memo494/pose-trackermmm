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
2. In the corner text, read the line that starts with `det`, for example `det 1/1 max 0.84 in 640 imp`, and
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

## What to send back
1. The **diagnostics zip** (press **Share diagnostics** right after the problem, not hours later).
2. **One sentence** about what was on the screen (which app, how many people, what you did).
3. The **block number** of this checklist that failed (for example "block 5"; for the M0c section say "M0c block 5").
4. A **screenshot** if the corner text or a warning looked strange.
5. If everything passed: say "all blocks passed" and the build id from the top of the app.

## For the agent (not for the owner)
These checks need a cable and `adb`, or the benchmark screen of M1, and are not part of the steps above:
- logcat must not show "Untrusted touch due to occlusion" while tapping through the overlay;
- `now - image.timestamp` must be within 0 to 30 ms (the `ts=` value in the zip summary tells whether the
  fallback was used: `arrivalFallbacks` should stay near 0);
- dot A/B comparison: head shift under 0.03 of the person height, keypoint 0 to 4 confidence drop under 0.05.
  On the phone the A/B is the switch **Debug marks (box and body points)**: run the same video once with it off
  (ring and corner marks) and once with it on (box and body points), and compare the `max` number of the `det`
  line and how often the marks blink (M0c block 6). Offline, the dots and the box already lowered the next
  frame's score (`docs/plans/M0c.md`, "Measured after T1").
