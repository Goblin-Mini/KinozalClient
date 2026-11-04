# Kinozal Client

Android client for Kinozal with Android TV, phone and tablet support, designed for integration with TorrServe MatriX.

## Project status

Initial scaffold. The first milestone establishes a reproducible Android build and app foundation. Kinozal authentication/session handling, dynamic catalog parsing, categories, search, sorting, comments, Android TV channels and TorrServe playback will be implemented and verified incrementally.

## Build

GitHub Actions builds a debug APK on pushes to `main` and pull requests. The APK is published as a workflow artifact when the build succeeds.

## Security

Never commit Kinozal passwords, session cookies, private API tokens or private TorrServe endpoints.
