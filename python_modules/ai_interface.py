import sys
import time

def chat(prompt):
    # Mock AI response
    # In real implementation, call OpenAI API or local LLM here.
    time.sleep(1) # Simulate thinking
    print(f"Ghost AI: I received your request '{prompt}'. As a Ghost, I can help you with code.")

if __name__ == "__main__":
    if len(sys.argv) > 1:
        prompt = " ".join(sys.argv[1:])
        chat(prompt)
    else:
        print("Usage: python ai_interface.py [prompt]")
