#!/usr/bin/env bash
# Gemeinsame Funktionen fuer die Emulator-Regression (Box: 8 Kerne, 15 GB RAM, immer nur ein Emulator).
export JAVA_HOME=${JAVA_HOME:-$HOME/tools/jdk-17.0.20.1+1}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/tools/android-sdk}
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH
export ANDROID_AVD_HOME=${ANDROID_AVD_HOME:-$HOME/.android/avd}
EMU_PORT=${EMU_PORT:-5554}
SER=emulator-$EMU_PORT

# api_name API -> Android-Version fuer den Bericht
android_name() { case $1 in 33) echo "Android 13";; 34) echo "Android 14";; 35) echo "Android 15";; 36) echo "Android 16";; *) echo "API $1";; esac; }

create_avd() { # API
  local api=$1 n=api$1
  if [ -d "$ANDROID_AVD_HOME/$n.avd" ]; then return 0; fi
  echo no | avdmanager create avd -n "$n" -k "system-images;android-$api;google_apis;x86_64" -d pixel_6 --force >/dev/null
  local ini="$ANDROID_AVD_HOME/$n.avd/config.ini"
  sed -i '/^hw.ramSize/d;/^disk.dataPartition.size/d;/^hw.cpu.ncore/d' "$ini"
  printf 'hw.ramSize=3072\ndisk.dataPartition.size=6G\nhw.cpu.ncore=4\nhw.keyboard=yes\n' >> "$ini"
}

kvm_ok() { sudo -n -u "$USER" -g kvm test -w /dev/kvm; }

# EMU_ACCEL=off (Standard auf der Box) oder on. BEFUND 0.2.9: /dev/kvm der Box meldet vmx, aber KVM_CREATE_VCPU endet im Kernel mit
# "kernel BUG at arch/x86/kvm/x86.c:702 (kvm_spurious_fault)", jeder Versuch erzeugt einen Kernel-Oops. Deshalb Standard ohne KVM (TCG, langsam).
# Mit on laeuft der Emulator unter Gruppe kvm (gid 103) und /dev/kvm. NICHT auf der Box verwenden, solange der Befund gilt.
EMU_ACCEL=${EMU_ACCEL:-off}

# start_emulator API -> druckt Startzeit in Sekunden (bis sys.boot_completed) auf stdout, Meldungen auf stderr
start_emulator() {
  local api=$1 n=api$1 log=${EMU_LOG:-/tmp/emu-$1.log}
  stop_emulator
  rm -f "$ANDROID_AVD_HOME/$n.avd/"*.lock
  : > "$log"
  local t0=$(date +%s)
  local common=(-avd "$n" -port "$EMU_PORT" -no-window -no-audio -no-boot-anim -no-snapshot -no-metrics -gpu swiftshader_indirect -memory 3072 -no-nested-warnings)
  if [ "$EMU_ACCEL" = on ]; then
    setsid nohup sudo -n -u "$USER" -g kvm env HOME="$HOME" PATH="$PATH" ANDROID_HOME="$ANDROID_HOME" ANDROID_SDK_ROOT="$ANDROID_HOME" ANDROID_AVD_HOME="$ANDROID_AVD_HOME" \
      emulator "${common[@]}" -cores 4 > "$log" 2>&1 < /dev/null &
  else
    setsid nohup emulator "${common[@]}" -accel off -cores ${EMU_CORES:-6} > "$log" 2>&1 < /dev/null &
  fi
  adb start-server >/dev/null 2>&1
  local i=0 limit=${EMU_BOOT_LIMIT:-1800}
  until [ "$(adb -s $SER shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 5; i=$((i+5))
    if [ $i -gt $limit ]; then echo "Zeitueberschreitung beim Start (${limit} s)" >&2; return 1; fi
    if ! pgrep -x qemu-system-x86 >/dev/null; then echo "Emulator beendet" >&2; tail -5 "$log" >&2; return 1; fi
  done
  echo $(( $(date +%s) - t0 ))
}

stop_emulator() {
  timeout 10 adb -s $SER emu kill >/dev/null 2>&1 || true
  sleep 2
  # Prozessnamen exakt (comm ist auf 15 Zeichen gekuerzt), damit pkill nie die eigene Shell trifft
  pkill -9 -x qemu-system-x86 >/dev/null 2>&1 || true
  pkill -9 -x emulator >/dev/null 2>&1 || true
  sleep 1
}

A() { adb -s $SER "$@"; }
