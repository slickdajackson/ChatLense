# ChatLens privacy policy (draft)

As of version 0.3.0. This is a draft, not legal advice. Before publication, fill in the controller, address, and contact, and have the text reviewed legally.

## Controller
[Enter name and address]
Contact: [Enter email]

## What ChatLens does
ChatLens uses the Android accessibility service to read the text of a chat you opened yourself in WhatsApp. From that it produces analyses, reply suggestions, and a memory with profiles of the people you talk to. ChatLens never sends messages on its own.

## Which data is processed
- Chat content visible on screen in WhatsApp (names, message text, times), only during a task you start.
- Optional voice messages from a folder you grant access to, converted to text on the device.
- Optional screenshots of individual images, for text recognition on the device.
- Profiles for each chat, and your self profile, in memory.
- App settings.

## Where the data stays
- Everything stays on your device only, in private app storage, encrypted with AES-GCM. The key is in the Android Keystore. There is no cloud backup (allowBackup is off).
- Models run locally on the device. Chat content is not sent to a server.
- ChatLens contains no ads, no analytics, and no sharing with third parties.

## Optional: API mode (full build only)
In the full build (not the Play build) you can enter your own API server. Chat text and, optionally, images then go to that server. That is a transfer of other people's personal data and needs a legal basis. The GDPR household exemption applies only to purely private use.

## Permissions
- Accessibility: to read the open chat and, after you confirm, to place a draft in the input field. ChatLens never presses the send button.
- Display over other apps: for the floating dot (optional).
- Notifications: to show a running task with a way to cancel (optional).

## Deletion
On the Memory tab you can delete individual profiles or everything. Uninstalling the app deletes all data.

## Your rights
You have the rights of access, rectification, erasure, restriction, data portability, and objection, and the right to lodge a complaint with a supervisory authority. Because ChatLens does not transmit data to the provider, the data stays with you.

## Note on WhatsApp
WhatsApp's terms of service prohibit automated access. An account ban is possible. You use ChatLens at your own risk.
