import subprocess
import time
import os
import sys

# Path to the run.bat script (relative to python_modules)
# Assuming python_modules/watchdog.py
# And scripts/run.bat
RUN_SCRIPT = os.path.join("..", "scripts", "run.bat")

def monitor():
    print("Ghost Watchdog Started. Monitoring Ghost Application...")
    while True:
        print("Launching Ghost...")
        # Start the process
        if os.name == 'nt':
            # Use shell=True for bat files? Or just execute cmd /c
            # p = subprocess.Popen([RUN_SCRIPT], shell=True)
            # Better to run cmd /c run.bat to ensure it runs in a new window or same window?
            # If we want to hide it, we might need other flags.
            # For now, just run it.
            
            # We need to change CWD to scripts dir for run.bat to work correctly as currently written?
            # run.bat uses ..\lib, so it expects to be run from scripts/.
            
            os.chdir(os.path.join("..", "scripts"))
            p = subprocess.Popen(["run.bat"], shell=True)
            os.chdir(os.path.join("..", "python_modules")) # Restore cwd
            
        else:
            print("Non-Windows not supported yet.")
            return

        # Wait for it to finish
        exit_code = p.wait()
        
        print(f"Ghost exited with code {exit_code}. Restarting in 3 seconds...")
        time.sleep(3)

if __name__ == "__main__":
    monitor()
