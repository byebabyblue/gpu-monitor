"""Capture errors before PyInstaller's Qt/dependency runtime hooks execute."""
from backend_diagnostics import install_backend_diagnostics

install_backend_diagnostics()
