# lsd

Minimal dual-pane Android file manager, themed Gruvbox dark (UI, dialogs, menus and launcher icon). Kotlin, package `com.lsd.io`, arm64-ready, 16 KB page-size compatible.

**Does:** browse two folders side by side, long-press to select, Copy / Cut into the other pane (progress notification), receive files shared / opened from other apps,
Delete, file information, create files and folders, search, sort, browse ZIP and TAR contents, extract ZIP / TAR into the other pane, create uncompressed ZIP / TAR archives, APK icon thumbnails (plus image/video
thumbnails), tap an APK to install it. Hidden files are shown by default.
**Does not:** no internet permission; no compressed TAR variants such as `.tar.gz`.

## Using it

**Swipe left on any file or folder** to open its **File information** straight away (no menu). On a single row it shows that
item's properties; if the row is part of a multi-selection it shows the combined info of the whole selection (see below).

**Swipe right on any file or folder** (a short swipe is enough; the row does not move, the menu just opens) to get: Cut, Copy, Add to, Rename, Delete, File information. ZIP and TAR files also have **Extract to**. **Rename** (files and folders) appears only when a single item is
  targeted; with a multi-selection it is hidden.
- If the row is part of a multi-selection, the action applies to the whole selection. **File information** on a
  multi-selection shows combined info: item counts, total size, total files / folders inside, parent, newest / oldest date.

**Open ZIP / TAR:** tap a `.zip` or `.tar` file to preview the current folder's file and directory names. lsd reads archive metadata only; it does not extract entries or use a private browsing cache. ZIP uses its central directory; TAR is scanned sequentially. Large listings are populated in the background and capped to keep the pane responsive. Symlinks and special TAR entries are skipped.

**Add to:** swipe right on a file or folder, choose **Add to**, select ZIP or TAR, and enter an archive name. The archive is created in the current pane's folder with no compression. Existing names are kept; a numbered suffix is added to avoid overwriting. ZIP files use the stored method, and TAR files are plain TAR. Creation runs as a background operation with a progress notification.

**Extract to:** swipe right on one or more ZIP / TAR files, choose **Extract to**, and confirm. Contents are unpacked into the folder shown in the other pane. Existing files are kept and conflicting names get a numbered suffix. Unsafe paths and TAR links / special files are skipped.

**Cut / Copy** (from the swipe menu or the selection bar) ask right away, e.g. *"Do you want to move file X into /storage/emulated/0/ ?"*.
The destination is the folder shown in the **other pane**. OK starts the move / copy (progress notification), Cancel does nothing.
To move something, open the target folder in the other pane first (the mirror arrows help).

**Send to lsd (share from any app):** in another app's share sheet choose **Send to lsd**. Any file type, one or many files.
They are copied into lsd's private temp folder (`<app cache>/shared`) and the active pane opens it. Cut / Copy them from there
into the other pane's folder to keep them. Plain shared text with no file is saved as `shared_text.txt`.

**Open with lsd (any file type):** lsd also appears in the system "Open with" list (as "Copy to lsd temp") for any file
type, and works exactly like Send to lsd. Choosing it inside lsd itself on a file you tapped just says it is already here.
Reopen that folder any time via the 3-dot menu -> **Shared files**. It is a cache folder, so Android may clear it if
storage runs low; move out what you want to keep.

**File information** (Properties): Name, Parent, Type, Size, Modified, Permissions (`drwxrws---(2770)`), Owner,
Group and, for folders, File Count / Folder Count.
- Tap the **Parent** path to copy it. Long-press any other value to copy it.
- **MORE** shows the full and real path, link target, accessed/changed times, inode, link count, access flags and,
  for files, MD5 / SHA-1 / SHA-256 (tap a checksum to copy it).

**Text editor:** tap common text and source files (`.txt`, `.md`, `.sh`, `.py`, `.kt`, `.java`, `.json`, `.js`, `.html`, `.css`, `.xml`, `.yaml`, `.toml`, `.sql`, and others), shell dotfiles such as `.bash_history`, `.bash_profile`, `.bashrc`, `.zshrc`, `.profile`, `.cshrc`, `.kshrc`, or any extensionless file. Also supports less common source/config formats (F#, Haskell, Erlang, Elixir, Clojure, Scala, GraphQL, HCL, systemd units, editor configs and more). Files open in **view mode**: scrolling and single taps to place the caret do not show the keyboard. Double-tap the text or tap the **pencil** icon to edit; the keyboard and extra-keys row appear. Tap the pencil again to return to view mode.
The hamburger menu includes Go to line, Syntax selection, previous / next cursor position, Soft wrap and Read-only / Edit mode. Syntax can be selected per file from common and less common modes; **Text** disables coloring. Comments use Gruvbox comment styling and plain `.txt` files are not syntax-highlighted. The top bar has Undo, Redo, Save, Pencil, a **Jump** icon that switches between the start and end of the file, and a 3-dot menu (Select all, Copy all). Under it: file name (a dot marks unsaved changes) and the caret `line:column`. The text has logical line numbers, wrap markers and a highlighted current line. Files over ~400 KB open without colors to stay responsive. Press **Save** (disk icon) to write changes.

**Places (any pane):** the hamburger menu starts with **Internal storage**, every mounted **SD card / USB drive** (detected automatically) and **Termux private folder**. Each one opens in whichever pane is active, so you can put Termux in one pane and the SD card in the other and Copy / Cut between them.

**Termux private folder:** Termux keeps its files in `/data/data/com.termux/files`, which no other app can open directly. lsd reaches it through Termux's own document provider. The first time, choose hamburger menu -> **Termux private folder (connect)**: the system folder picker opens, pick Termux (its home folder) and tap **Use this folder**. After that it opens in one tap and shows the real Termux paths (`/data/data/com.termux/files/home/...`). Browse, Copy / Cut in both directions, Rename, Delete, create files and folders, file information and the text editor all work there. Delete there removes symlinks (such as `~/storage/shared`) without entering them, so their targets are never touched. Limits: ZIP / TAR can't be previewed, extracted or created inside the Termux folder (copy them to shared storage first), no thumbnails, and files opened in other apps are handed over as a temporary copy. Termux must be installed; the permission can be revoked any time in Android's settings.

**Local storage shortcuts:** use the hamburger menu -> **Add local storage…** to select any other shared-storage folder or SD-card folder. It is saved as a shortcut in that menu and opens in the active pane.

**Address bar:** tap the folder path at the top to copy it to the clipboard. Copying never shows an lsd toast (Android shows its own confirmation).

**Bottom toolbar** (left to right): Back, Forward, **+**, **Mirror arrows**, Up.
- **+** creates in the active pane. Type a name and it becomes a file (with or without an extension);
  end the name with `/` and it becomes a folder. `a/b/c/` and `a/b.txt` create the missing parent folders.
- **Mirror arrows** make the *other* pane jump to the folder the active pane is in, so after drilling deep into
  one pane you can open the same place in the other with one tap. (Touch a pane to make it the active one.)

**3-dot menu (top right)**, in order: Search, Select all, Sort, Set as Home, Shared files, Refresh.
- **Search** looks recursively for names containing your text below the current folder and shows the hits in
  that pane (with their sub-folder). Back / Up leaves the search; tapping a folder hit opens it.
- **Sort** (per pane): by Name, Size, Date or Type, with a Reverse order checkbox. Folders always stay on top.
- **Set as Home** makes the active pane start in its current folder, with its current sort order, every time the
  app opens. Each pane has its own home and sort. Open the menu again and choose **Remove home** to go back to
  the storage root and the default sort (to change a saved sort, Remove home and then Set as Home again).

The hamburger menu on the left lists the places above, toggles hidden files (shown by default), has Select all, and stores added local-storage shortcuts.

## Build and release on GitHub
1. Push this project to GitHub with the `.github` folder included. The Android workflow builds three APK downloads:
   `arm64-v8a`, `armeabi-v7a`, and `universal`. This app currently has no native libraries, so all three contain the same app code.
2. Push to `main` / `master`, open a pull request, or run **Actions -> Build & Release lsd APKs** to get downloadable build artifacts.
3. To publish a GitHub Release, push a version tag such as `v1.2.0` (or provide one in `workflow_dispatch`). Release tags use semantic versions and set Android's version name and increasing version code.
4. Configure `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` as repository secrets before publishing releases. The same signing key is then used across releases so they can update an existing install. Untagged builds use the debug key when no signing secrets are configured.

No Gradle wrapper is included; the workflow installs Gradle 9.8.0 itself.
For Android Studio / local builds run `gradle wrapper --gradle-version 9.8.0` once.

## Notes
- Gradle 9.8.0, AGP 9.5.1 (built-in Kotlin), compileSdk/targetSdk 36 (Android 16), minSdk 30, JDK 17.
- AGP 9.x needs Gradle 9.6+, JDK 17 and SDK build-tools 36.0.0, and compiles Kotlin itself, so the
  `org.jetbrains.kotlin.android` plugin is not used. Keep Gradle 9.x and AGP 9.x together: Gradle 9 breaks
  AGP 8.x, and Gradle 8.x gets the "out-of-date Gradle" warning.
- No native code; ZIP uses Java's standard library and TAR uses Apache Commons Compress. The workflow rejects unexpected native libraries and enforces the `zipalign -P 16` check.
- Name clashes when pasting are auto-renamed: `name (1).ext`.
- Delete is permanent (no trash) and never follows symlinks.

## Theme
Gruvbox dark. Palette lives in `res/values/colors.xml` (bg `#282828` / `#1D2021`, fg `#EBDBB2`, accent yellow `#FABD2F`);
dialogs and popup menus are styled in `res/values/styles.xml` (`LsdDialog`, `LsdPopupMenu`). The launcher icon is a
dual-pane folder in two yellows on the `#282828` background. Folders are tinted yellow, files in the foreground colour.
---
Code is GPLv3. Icons and branding are not; see TRADEMARK.md
