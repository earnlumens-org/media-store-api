# Media Store API

## Description
**Media Store API** is a robust RESTful service designed for **Multimedia Content Distribution Management**, leveraging the power of the **Stellar Network** for secure, transparent, and efficient transactions.

This backend is part of the official EarnLumens infrastructure, handling media storage, user management, and communication with the Stellar Network.

In addition to managing multimedia content, this service handles users, wallets, authentication, XDR generation, and the logic necessary to coordinate operations with the Stellar Network within the EarnLumens ecosystem.

## Architecture (Overview)
- **Spring Boot Backend** → REST API + business logic.
- **Database** → Persistence for users, media, and metadata.
- **Media Storage / CDN** → File storage.
- **Stellar Network** → Creation/validation of signed transactions.
- **Frontend Vue/Vuetify** → Consumes the API to manage content and payments.

## Features
- **Multimedia Content Management**: Upload, organize, and distribute digital media assets.
- **Stellar Network Integration**: 
  - Fast and low-cost transactions.
  - Asset issuance and management on the Stellar blockchain.
- **RESTful Architecture**: Clean and standard endpoints for easy integration.
- **Scalable Design**: Built with Spring Boot for high performance and reliability.

## Tech Stack
- **Language**: Java 21
- **Framework**: Spring Boot 4.x
- **Build Tool**: Gradle
- **Blockchain**: Stellar Network

## Prerequisites
- **Java Development Kit (JDK) 21**: Ensure Java 21 is installed and configured.
- **Gradle**: (Optional if using the included wrapper).

## Getting Started

### 1. Clone the repository
```bash
git clone <repository-url>
cd media-store-api
```

### 2. Configuration
The application uses properties files for configuration.
- `src/main/resources/application.properties`: Main configuration.
- `src/main/resources/application-dev.properties`: Development specific settings (Create this file if needed for local overrides, it is git-ignored).

### 3. Run the Application
You can run the application using the Gradle wrapper:

**On macOS/Linux:**
```bash
./gradlew bootRun
```

**On Windows:**
```bash
gradlew.bat bootRun
```

The server will start at `http://localhost:8080`.

## API Documentation
(TODO: Add Swagger/OpenAPI link once integrated)

### Security Architecture
See full system architecture here:  
[docs/security-architecture.md](./docs/security-architecture.md)
[docs/backend-security.md](./docs/backend-security.md)

## 🔐 License
MIT – use it freely and responsibly.
