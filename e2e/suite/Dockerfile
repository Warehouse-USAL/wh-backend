FROM python:3.12-slim
WORKDIR /suite
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
COPY . .
ENTRYPOINT ["sh", "-c", "eval exec python -m pytest $PYTEST_ARGS"]
