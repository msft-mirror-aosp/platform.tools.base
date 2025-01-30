FROM ubuntu:jammy

RUN apt-get update && DEBIAN_FRONTEND="noninteractive" TZ="America/Los_Angeles" apt-get install -y \
  build-essential \
  curl \
  python3 \
  golang-go \
  file \
  zip \
  git \
  unzip

RUN apt-get update && apt-get --no-install-recommends install -y ca-certificates-java locales && rm -rf /var/lib/apt/lists/*
# Install lib32ncurses5 for sdk linker dependency on 32 bit libraries.
RUN apt-get update && apt-get install -y software-properties-common && add-apt-repository universe
RUN apt-get update && apt-get install -y libncurses5 lib32z1
# Install dependencies for emulator.
RUN apt-get update && apt-get install -y libpulse-dev libgl1-mesa-glx libxcomposite-dev libxcursor-dev libasound2 libxkbfile-dev libxcb-cursor0 libsm6 libxext6 libxrender-dev
# https://doc.qt.io/qt-6/linux-requirements.html#platform-plugin-dependencies
RUN apt-get update && apt-get install -y libfontconfig1-dev \
    libfreetype-dev \
    libx11-dev \
    libx11-xcb-dev \
    libxcb-cursor-dev \
    libxcb-glx0-dev \
    libxcb-icccm4-dev \
    libxcb-image0-dev \
    libxcb-keysyms1-dev \
    libxcb-randr0-dev \
    libxcb-render-util0-dev \
    libxcb-shape0-dev \
    libxcb-shm0-dev \
    libxcb-sync-dev \
    libxcb-util-dev \
    libxcb-xfixes0-dev \
    libxcb-xinerama0-dev \
    libxcb-xkb-dev \
    libxcb1-dev \
    libxext-dev \
    libxfixes-dev \
    libxi-dev \
    libxkbcommon-dev \
    libxkbcommon-x11-dev \
    libxrender-dev

# Integration testing deps
RUN apt-get install -y libxdamage1 libxtst6 libxi6
# Container uses dockerUseURandom to mount /dev/urandom as /dev/random.
ENV LANG en_US.UTF-8
ENV LANGUAGE en_US:en
ENV LC_ALL en_US.UTF-8
RUN echo en_US.UTF-8 UTF-8 >> /etc/locale.gen
RUN locale-gen en_US.UTF-8
# For NDK bug (b/123781809)
ENV LD_LIBRARY_PATH ""
ENV CC ""
# Set a variable we can test to make sure we are using correct Docker Image
ENV STUDIO_IMAGE=true
