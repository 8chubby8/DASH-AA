/* ===========================================================================
   DASH Module — Car Power Tester        |  two modules on one board:
                                          |    SYSTEM   "Power Tester"  (the car)
                                          |    LISTENER "Power Tester Outputs"
   Board: any ESP32 (classic DevKit, S2, S3, C3, C6)  |  transport: USB serial
   Built on the DashModule library.  For DASH-AA 1.1.7.
   ---------------------------------------------------------------------------
   A pretend car for trying DASH's car power on the bench. It plays a day in
   the car — unlock, door, ignition, a crank, a drive, ignition off, lock — as
   the signals a real body/engine module would send, and listens for what DASH
   does about it: the stage it is in (power_state) and the eight switched
   outputs (power_output_1 … _8), shown on the board's LED and, if you wire
   them, on eight more LEDs.

   WHAT IT SENDS (SYSTEM)                 WHAT IT HEARS (LISTENER)
     ignition_state   off/accessory/on      power_state     waking/ready/parked/stopping/off
     engine_running   true/false            power_output_1 … power_output_8
     doors_locked     true/false            screen_on, sound_ready
     door_driver_open true/false
     battery_voltage  volts
     head_unit_awake  (only if POWER_MODULE below is 1)

   THE BOOT BUTTON (the one on every ESP32 board, GPIO0)
     short press          next step of the day, by hand (stops the automatic day)
     hold 1 s             start / stop the automatic day
     hold 3 s             flat battery: the voltage drops to 11.2 V (hold again to restore)

   THE LED (the board's own; colour on boards with an RGB LED)
     waking   slow blink   (blue)        parked    double blink (amber)
     ready    on           (green)       stopping  fast blink   (red)
     off / no DASH yet: dark

   !! At the end of the day the car is locked, and with DASH's default
   !! settings (Power › Car: When the car is left → Sleep, When the doors lock
   !! → On) DASH puts the machine to sleep. That is the test. A serial module
   !! cannot wake a sleeping machine — wake it with its power button or lid.
   !! Then the day starts again.

   ONE BOARD, TWO MODULES (module-sdk.md §4a): a module does one job, so the
   board presents two — each with its own id and its own install in DASH's
   Module Manager. They share the one USB cable; the little Face class below
   hands every line DASH sends to both, and each answers only for its own id.

   Install both from Modules › Module Manager. Then watch Power › Car, Power ›
   Outputs (set some outputs to Awake / Ignition / Engine / Sound) and
   Modules › Signal Monitor.
   =========================================================================== */
#include <Dash.h>

// 1: also behave as a dedicated power module, sending head_unit_awake — true while
// the car is unlocked or the ignition is on, false 5 s after it is locked. DASH obeys
// it over its own rules. 0: send only the facts, and DASH's Power › Car settings decide.
#define POWER_MODULE 0

// Optional LEDs for the eight outputs (through a resistor to GND). -1 = not fitted.
const int OUTPUT_PINS[8] = {-1, -1, -1, -1, -1, -1, -1, -1};

const int BUTTON_PIN = 0;   // BOOT

/* -------- two faces on one USB serial ------------------------------------- */
// Each module reads its own copy of what DASH sends; both write straight to USB.
// The library's writes happen inside one face's loop() at a time, so a line from
// one face is never split by the other.
class Face : public Stream {
 public:
  void push(uint8_t c) {
    uint16_t n = (head + 1) % sizeof(buf);
    if (n != tail) { buf[head] = c; head = n; }
  }
  int available() override { return (head + sizeof(buf) - tail) % sizeof(buf); }
  int read() override {
    if (head == tail) return -1;
    uint8_t c = buf[tail];
    tail = (tail + 1) % sizeof(buf);
    return c;
  }
  int peek() override { return head == tail ? -1 : buf[tail]; }
  size_t write(uint8_t c) override { return Serial.write(c); }
  size_t write(const uint8_t* b, size_t n) override { return Serial.write(b, n); }
  void flush() override { Serial.flush(); }
 private:
  uint8_t buf[512];
  uint16_t head = 0, tail = 0;
};
Face carWire, outputsWire;

DashSystem   car("0000DA580A71", "Power Tester", "Pretend car: ignition, locks, battery", "v1.0");
DashListener outputs("0000DA580A72", "Power Tester Outputs", "Shows DASH's power stage and outputs", "v1.0");

/* -------- the pretend car ------------------------------------------------- */
const char* ignition = "off";
bool engine = false, locked = true, doorOpen = false, flatBattery = false;
float volts = 12.6;
unsigned long lockedAt = 0;

bool headUnitAwake() { return !locked || strcmp(ignition, "off") != 0 || millis() - lockedAt < 5000; }

void report() {
  car.broadcast("ignition_state", ignition);
  car.broadcast("engine_running", engine ? "true" : "false");
  car.broadcast("doors_locked", locked ? "true" : "false");
  car.broadcast("door_driver_open", doorOpen ? "true" : "false");
  car.broadcast("battery_voltage", (double)(flatBattery ? 11.2 : volts), 1);
#if POWER_MODULE
  car.broadcast("head_unit_awake", headUnitAwake() ? "true" : "false");
#endif
}

void setIgnition(const char* v) { ignition = v; car.broadcast("ignition_state", v); }
void setEngine(bool v) { engine = v; volts = v ? 14.2 : 12.5; car.broadcast("engine_running", v ? "true" : "false"); report(); }
void setLocked(bool v) { locked = v; if (v) lockedAt = millis(); car.broadcast("doors_locked", v ? "true" : "false"); }
void setDoor(bool v) { doorOpen = v; car.broadcast("door_driver_open", v ? "true" : "false"); }

/* -------- the day in the car: one step each, and how long it lasts ---------- */
struct Step { const char* what; unsigned long ms; };
const Step DAY[] = {
  {"unlock",         12000},   // DASH: Waking (screen dark, phone connecting)
  {"door open",       3000},
  {"door shut",       5000},
  {"accessory",      10000},   // DASH: Ready (screen on, splash, outputs come on)
  {"crank",           1500},   // the ignition drops while the starter turns — DASH must ride it out
  {"engine running", 40000},   // a drive
  {"engine off",      6000},   // back to accessory
  {"ignition off",   15000},   // DASH: Parked (screen dark, outputs off)
  {"door open",       3000},
  {"door shut",       6000},
  {"lock",           60000},   // DASH: leaves (sleeps); the day starts again after a minute
};
const int DAY_STEPS = sizeof(DAY) / sizeof(DAY[0]);
int step = -1;
bool automatic = true;
unsigned long stepAt = 0;

void doStep(int i) {
  const char* w = DAY[i].what;
  if      (!strcmp(w, "unlock"))         setLocked(false);
  else if (!strcmp(w, "door open"))      setDoor(true);
  else if (!strcmp(w, "door shut"))      setDoor(false);
  else if (!strcmp(w, "accessory"))      setIgnition("accessory");
  else if (!strcmp(w, "crank"))          setIgnition("off");
  else if (!strcmp(w, "engine running")) { setIgnition("on"); setEngine(true); }
  else if (!strcmp(w, "engine off"))     { setEngine(false); setIgnition("accessory"); }
  else if (!strcmp(w, "ignition off"))   setIgnition("off");
  else if (!strcmp(w, "lock"))           setLocked(true);
#if POWER_MODULE
  car.broadcast("head_unit_awake", headUnitAwake() ? "true" : "false");
#endif
}

void nextStep() {
  step = (step + 1) % DAY_STEPS;
  stepAt = millis();
  doStep(step);
}

void onCarActive() { step = -1; stepAt = millis(); }

/* -------- what DASH says back --------------------------------------------- */
char stage[12] = "";
bool outputOn[8] = {false};

void onSignal(const char* function, const char* value) {
  if (!strcmp(function, "power_state")) {
    strncpy(stage, value, sizeof(stage) - 1);
  } else if (!strncmp(function, "power_output_", 13)) {
    int n = atoi(function + 13);
    if (n >= 1 && n <= 8) {
      outputOn[n - 1] = !strcmp(value, "true");
      if (OUTPUT_PINS[n - 1] >= 0) digitalWrite(OUTPUT_PINS[n - 1], outputOn[n - 1] ? HIGH : LOW);
    }
  }
}

void onOutputsSilent() {
  stage[0] = '\0';
  for (int n = 0; n < 8; n++) { outputOn[n] = false; if (OUTPUT_PINS[n] >= 0) digitalWrite(OUTPUT_PINS[n], LOW); }
}

/* -------- the LED --------------------------------------------------------- */
#ifndef LED_BUILTIN
#define LED_BUILTIN 2
#endif

void led(bool lit, uint8_t r, uint8_t g, uint8_t b) {
#ifdef RGB_BUILTIN
  rgbLedWrite(RGB_BUILTIN, lit ? r / 4 : 0, lit ? g / 4 : 0, lit ? b / 4 : 0);
#else
  digitalWrite(LED_BUILTIN, lit ? HIGH : LOW);
#endif
}

void showStage() {
  unsigned long t = millis();
  if (!strcmp(stage, "ready"))         led(true, 0, 255, 0);
  else if (!strcmp(stage, "waking"))   led((t / 1000) % 2 == 0, 0, 0, 255);
  else if (!strcmp(stage, "parked"))   { unsigned long p = t % 1500; led(p < 150 || (p > 300 && p < 450), 255, 120, 0); }
  else if (!strcmp(stage, "stopping")) led((t / 100) % 2 == 0, 255, 0, 0);
  else                                 led(false, 0, 0, 0);
}

/* -------- the button ------------------------------------------------------ */
unsigned long pressedAt = 0;
bool wasDown = false, heldOne = false, heldThree = false;

void readButton() {
  bool down = digitalRead(BUTTON_PIN) == LOW;
  unsigned long now = millis();
  if (down && !wasDown) { pressedAt = now; heldOne = heldThree = false; }
  if (down && !heldThree && now - pressedAt >= 3000) {
    heldThree = true;
    flatBattery = !flatBattery;
    car.broadcast("battery_voltage", (double)(flatBattery ? 11.2 : volts), 1);
  }
  if (!down && wasDown && now - pressedAt > 30) {
    unsigned long held = now - pressedAt;
    if (held < 1000) { automatic = false; nextStep(); }
    else if (held < 3000) { automatic = !automatic; stepAt = now; }
  }
  wasDown = down;
}

void setup() {
  Serial.begin(115200);
  pinMode(BUTTON_PIN, INPUT_PULLUP);
#ifndef RGB_BUILTIN
  pinMode(LED_BUILTIN, OUTPUT);
#endif
  for (int n = 0; n < 8; n++) if (OUTPUT_PINS[n] >= 0) { pinMode(OUTPUT_PINS[n], OUTPUT); digitalWrite(OUTPUT_PINS[n], LOW); }

  car.begin(carWire);
  car.addSignal("ignition_state");
  car.addSignal("engine_running");
  car.addSignal("doors_locked");
  car.addSignal("door_driver_open");
  car.addSignal("battery_voltage");
#if POWER_MODULE
  car.addSignal("head_unit_awake");
#endif
  car.onReport(report);
  car.onActivate(onCarActive);

  outputs.begin(outputsWire);
  outputs.subscribe("power_state");
  for (int n = 1; n <= 8; n++) {
    static char names[8][16];
    snprintf(names[n - 1], sizeof(names[n - 1]), "power_output_%d", n);
    outputs.subscribe(names[n - 1]);
  }
  outputs.subscribe("screen_on");
  outputs.subscribe("sound_ready");
  outputs.onSignal(onSignal);
  outputs.onDeactivate(onOutputsSilent);
}

void loop() {
  while (Serial.available()) {
    uint8_t c = Serial.read();
    carWire.push(c);
    outputsWire.push(c);
  }
  car.loop();
  outputs.loop();
  readButton();
  showStage();

  if (car.isActive() && automatic) {
    unsigned long wait = step < 0 ? 5000 : DAY[step].ms;
    if (millis() - stepAt >= wait) nextStep();
  }
}
