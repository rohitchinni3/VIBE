import os
import wave
from pydub import AudioSegment

# Folder containing WAV files
wav_folder = "/Users/rohit/Desktop/IIT_M/16967__kwahmah_02__doorbell"
# Folder to save standardized WAV files
output_folder = "/Users/rohit/Desktop/IIT_M/new_dataset/doorbell"

# Desired properties for all WAV files
TARGET_SAMPLE_RATE = 16000  # e.g., 16 kHz
TARGET_CHANNELS = 1         # Mono
TARGET_BIT_DEPTH = 16       # 16-bit audio

# Ensure the output folder exists
os.makedirs(output_folder, exist_ok=True)

def check_and_fix_wav(file_path, output_path):
    """Check WAV file properties and standardize them if necessary."""
    try:
        # Open the WAV file to read its properties
        with wave.open(file_path, 'rb') as wav_file:
            sample_rate = wav_file.getframerate()
            channels = wav_file.getnchannels()
            bit_depth = wav_file.getsampwidth() * 8
            
        # Log current properties
        print(f"{os.path.basename(file_path)}: "
              f"Sample Rate = {sample_rate} Hz, Channels = {channels}, Bit Depth = {bit_depth}-bit")

        # Check if the WAV file needs to be standardized
        if (sample_rate != TARGET_SAMPLE_RATE or
            channels != TARGET_CHANNELS or
            bit_depth != TARGET_BIT_DEPTH):
            print(f"Standardizing {os.path.basename(file_path)}...")

            # Load audio using pydub
            audio = AudioSegment.from_file(file_path)
            # Standardize the audio properties
            audio = audio.set_frame_rate(TARGET_SAMPLE_RATE)
            audio = audio.set_channels(TARGET_CHANNELS)
            audio = audio.set_sample_width(TARGET_BIT_DEPTH // 8)

            # Export the standardized audio to the output folder
            audio.export(output_path, format="wav")
            print(f"Standardized and saved: {output_path}")
        else:
            print(f"No changes needed for: {os.path.basename(file_path)}")
    except Exception as e:
        print(f"Error processing {file_path}: {e}")

# Iterate through all WAV files in the folder
for file_name in os.listdir(wav_folder):
    if file_name.endswith(".wav"):
        # Input and output paths
        wav_path = os.path.join(wav_folder, file_name)
        output_path = os.path.join(output_folder, file_name)
        # Check and fix the WAV file
        check_and_fix_wav(wav_path, output_path)

print("Processing complete.")
