@echo off
rem Pulsar QQ 音乐下载服务启动脚本
rem 依赖：fastapi uvicorn requests mutagen frida==16.7.18（已装） + ffmpeg 在 PATH
cd /d "%~dp0"
python qqmusic_service.py
pause
