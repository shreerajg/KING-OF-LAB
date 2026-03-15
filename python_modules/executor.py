import sys
import os
import subprocess
import ctypes

def is_admin():
    """Check if script is running with admin privileges"""
    try:
        return ctypes.windll.shell32.IsUserAnAdmin()
    except:
        return False

def execute_command(command):
    if command == "lock":
        if os.name == 'nt':
            os.system("rundll32.exe user32.dll,LockWorkStation")
        else:
            print("Lock not supported on non-Windows yet")
            
    elif command == "shutdown":
        if os.name == 'nt':
            os.system("shutdown /s /t 0")
            
    elif command == "restart":
        if os.name == 'nt':
            os.system("shutdown /r /t 0")
            
    elif command == "kill_net":
        if os.name == 'nt':
            # Disable all network adapters
            result = subprocess.run(
                ['powershell', '-Command', 
                 'Get-NetAdapter | Where-Object {$_.Status -eq "Up"} | Disable-NetAdapter -Confirm:$false'],
                capture_output=True, text=True, shell=True
            )
            if result.returncode != 0:
                # Fallback to netsh
                subprocess.run(['netsh', 'interface', 'set', 'interface', 'Wi-Fi', 'admin=disable'], shell=True)
                subprocess.run(['netsh', 'interface', 'set', 'interface', 'Ethernet', 'admin=disable'], shell=True)
            print("Network disabled")

    elif command == "restore_net":
        if os.name == 'nt':
            # Enable all network adapters
            result = subprocess.run(
                ['powershell', '-Command', 
                 'Get-NetAdapter | Enable-NetAdapter -Confirm:$false'],
                capture_output=True, text=True, shell=True
            )
            if result.returncode != 0:
                subprocess.run(['netsh', 'interface', 'set', 'interface', 'Wi-Fi', 'admin=enable'], shell=True)
                subprocess.run(['netsh', 'interface', 'set', 'interface', 'Ethernet', 'admin=enable'], shell=True)
            print("Network restored")

    elif command == "block_input":
        # Block keyboard/mouse (requires admin)
        if os.name == 'nt' and is_admin():
            ctypes.windll.user32.BlockInput(True)
            print("Input blocked")

    elif command == "unblock_input":
        if os.name == 'nt':
            ctypes.windll.user32.BlockInput(False)
            print("Input unblocked")

    elif command == "mute":
        # Mute system audio
        if os.name == 'nt':
            subprocess.run(['powershell', '-Command', 
                '(New-Object -ComObject WScript.Shell).SendKeys([char]173)'], shell=True)
            print("Audio muted")

    elif command == "screenshot":
        # Take a screenshot (for debugging)
        try:
            from PIL import ImageGrab
            img = ImageGrab.grab()
            img.save("screenshot.png")
            print("Screenshot saved")
        except ImportError:
            print("PIL not installed")
            
    else:
        # Execute as shell command
        try:
            result = subprocess.run(command, shell=True, capture_output=True, text=True)
            print(result.stdout)
            if result.stderr:
                print(result.stderr)
        except Exception as e:
            print(f"Error executing: {e}")

if __name__ == "__main__":
    if len(sys.argv) > 1:
        cmd = sys.argv[1]
        execute_command(cmd)
    else:
        print("Usage: python executor.py [command]")
