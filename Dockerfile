FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        tesseract-ocr tesseract-ocr-eng tesseract-ocr-spa \
        imagemagick libheif-examples \
    && rm -rf /var/lib/apt/lists/*
COPY target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.profiles.active=prod"]
