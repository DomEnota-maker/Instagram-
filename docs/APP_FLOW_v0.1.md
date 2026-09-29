# MediaLoader — Application Flow v0.1

## Purpose

This document describes the user flow of the MediaLoader application.
It connects UX requirements and future implementation logic.

## Main principle

The application flow should be simple:

User action → Analysis → Preview → Selection → Download → History

---

# 1. Application launch

Flow:

```
Open app
  ↓
Main screen
```

Main screen contains:

- URL input field;
- Check button;
- recent downloads block.

---

# 2. Link input

Possible sources:

- manual paste;
- future share action from external apps;
- clipboard detection suggestion.

Example:

```
User inserts link
        ↓
Presses Check
```

---

# 3. Media analysis

States:

```
IDLE
 ↓
ANALYZING
 ↓
PREVIEW_AVAILABLE
```

Application determines:

- source provider;
- available media files;
- file types;
- previews;
- metadata.

---

# 4. Preview screen

For single media:

```
Preview
 ↓
Download
```

For multiple media:

```
Found media list
 ↓
All items selected by default
 ↓
User removes unnecessary items
 ↓
Download selected
```

Rules:

- default selection: all files;
- user can deselect items;
- show selected count.

Example:

```
Selected 5/5
```

---

# 5. Download process

States:

```
READY
 ↓
DOWNLOADING
 ↓
COMPLETED / FAILED
```

Show:

- current file;
- progress;
- status.

---

# 6. File saving

Default location:

```
Download/MediaLoader
```

User can change folder in settings.

File naming rules:

- preserve original filename;
- do not add app name, date or username;
- when multiple files have the same name, add numeric suffix.

Example:

```
photo.jpg
photo_1.jpg
photo_2.jpg
```

---

# 7. History

After successful download:

```
Completed
 ↓
History entry created
```

History stores:

- preview;
- filename;
- media type;
- size;
- date;
- location.

---

# 8. Settings

Initial settings:

- download folder;
- notifications;
- theme;
- restore deleted files;
- application version.

---

# Future extensions

Architecture should allow adding:

- Instagram provider;
- YouTube provider;
- other media sources.

Providers may use different extraction logic but must return a common media model.
