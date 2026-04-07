FROM python:3.12-slim

RUN pip install -r requirements.txt

COPY . .

RUN python3 manage.py makemigrations

RUN python3 manage.py migrate

EXPOSE 8000

CMD ["python3","manage.py","runserver","0.0.0.0:8000"]
