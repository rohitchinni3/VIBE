import os
from pydub import AudioSegment

# Folder containing MP3 files
input_folder = "/Users/rohit/Desktop/IIT_M/Dataset/Door knock"
# Folder to save converted WAV files
output_folder = "/Users/rohit/Desktop/IIT_M/Dataset/Door knock/edit"

# Ensure the output folder exists
os.makedirs(output_folder, exist_ok=True)

# Iterate through all files in the input folder
for file_name in os.listdir(input_folder):
    if file_name.endswith(".mp3"):
        # Full path to the MP3 file
        mp3_path = os.path.join(input_folder, file_name)
        # Change file extension to .wav for the output
        wav_path = os.path.join(output_folder, os.path.splitext(file_name)[0] + ".wav")
        
        try:
            # Convert MP3 to WAV
            audio = AudioSegment.from_mp3(mp3_path)
            audio.export(wav_path, format="wav")
            print(f"Converted: {file_name} -> {os.path.basename(wav_path)}")
        except Exception as e:
            print(f"Failed to convert {file_name}: {e}")
