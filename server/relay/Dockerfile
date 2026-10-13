# Image du relay server Hasan — le port 8767 est publié sur 127.0.0.1 de
# l'hôte par server/docker-compose.yml (jamais sur une interface publique).
FROM python:3.11-slim

RUN useradd --system --create-home --uid 10000 hasanrelay

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir --upgrade pip \
    && pip install --no-cache-dir -r requirements.txt

COPY . .

USER hasanrelay

EXPOSE 8767

CMD ["python", "server.py"]
