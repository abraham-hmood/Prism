# Implement Voice Cloning Integration

The goal is to integrate a voice cloning activity that allows users to record or upload audio to clone voices for the "Kokoro-82M" model.

## User Review Required

- The voice cloning activity uses an iOS-style UI consistent with Prism.
- The activity supports recording and file uploads.
- After cloning, a popup dialog will collect voice metadata (name, language, gender).
- Cloned voices will be selectable in the `KokoroVoicePicker`.
- Previewing voices in the picker will not close the popup.
- The desktop language page will be updated to intelligently select a voice based on language and gender, defaulting to the user's current choice.

## Proposed Changes

### [Speech Settings]

#### [MODIFY] [KokoroSettingsActivity.kt](file:///F:/AndroidStudioProjects/Prism/app/src/main/java/com/prism/launcher/speech/KokoroSettingsActivity.kt)
- Add "Clone a voice" row that opens `VoiceCloningActivity`.

#### [MODIFY] [KokoroVoicePicker.kt](file:///F:/AndroidStudioProjects/Prism/app/src/main/java/com/prism/launcher/speech/KokoroVoicePicker.kt)
- Update the voice selection dialog to allow previewing without closing the popup.

### [Language Desktop Page]

#### [MODIFY] [LanguagePage.kt](file:///F:/AndroidStudioProjects/Prism/desktop/src/main/kotlin/com/prism/desktop/ui/LanguagePage.kt)
- Update voice selection logic for tutors:
  - If the tutor's language is supported in `KokoroVoices`, automatically pick a voice that matches the language and the tutor's gender.
  - Otherwise, default to the user's selected voice.

## Verification Plan

### Automated Tests
- Verify voice picking logic in `LanguageVoiceTest.kt`.

### Manual Verification
- Open "Kokoro-82M Voice & Speech" settings.
- Tap "Clone a voice" and record/upload a sample.
- Save the new cloned voice.
- Verify the cloned voice appears in the voice picker.
- Preview voices in the picker.
- Use the language desktop page with different tutors to verify voice selection.
