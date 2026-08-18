"""Launch the GPU dashboard without a console window on Windows."""

import os
import runpy
from pathlib import Path


APP_DIR = Path(__file__).resolve().parent
os.chdir(APP_DIR)
runpy.run_path(str(APP_DIR / "gpu_dashboard.py"), run_name="__main__")
