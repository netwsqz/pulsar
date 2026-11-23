@echo off
rem Pulsar QQ 音乐下载服务启动脚本
rem 依赖：FastAPI 0.121.3 起、Starlette 0.49.1 起、uvicorn requests mutagen frida==16.7.18 + ffmpeg 在 PATH
cd /d "%~dp0"
python qqmusic_service.py
pause
