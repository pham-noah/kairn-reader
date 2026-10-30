# Kairn 0.8.4

Updates and restores land you back in your library, not in a scan.

**No rescan after an update or a restore**
- A book whose file link died - an old picker permission, or a record restored without its file - is
  re-attached to Kairn's own copy the next time the library opens.
- Files you pick are copied into Kairn's folder, as "Open with" already did.
- If something ever is missing, the device scan runs on its own instead of waiting for you to press it.

**Quieter storage**
- The device scan no longer runs on every open. A missing book makes it run, at most once an hour;
  "Find books on this device" still forces it.
- The search cache drops entries for books you removed, and its size is measured off the main thread.

**Settings you cannot lose**
- A settings file that will not parse no longer resets the app to defaults: one bad entry costs that entry,
  not the theme, the folders and the onboarding state. The unreadable file is kept as `settings.json.bak`.

Install over 0.8.3 as usual - same signing key, data kept.
