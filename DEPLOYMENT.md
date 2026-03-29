# King of Lab Deployment Instructions

## FFmpeg Bundling Requirement
This project relies on FFmpeg for screen capturing and video encoding. To ensure the application runs smoothly without requiring users to manually install FFmpeg, we bundle the official `ffmpeg` binaries along with the app.

### Directory Structure
When distributing the application, ensure the `ffmpeg` directory exists at the root of the application (alongside `src`, `lib`, and `scripts`), and contains the appropriate executable for the target platform:

- **Windows**: `./ffmpeg/ffmpeg.exe`
- **Linux/macOS**: `./ffmpeg/ffmpeg`

The application code (`FfmpegResolver.java`) is designed to look for the bundled binary in this specific `ffmpeg` subdirectory before falling back to the system `PATH`. 

### Generating the Bundle
If the FFmpeg binary is missing locally, run the provided script to download the latest essential build:
```powershell
# From the project root
powershell -ExecutionPolicy Bypass -File scripts\dl_ffmpeg.ps1
```

Once the `ffmpeg.exe` file is downloaded and placed into the `ffmpeg` folder, the environment is ready for packaging and deploying. **Do not omit this directory.**
