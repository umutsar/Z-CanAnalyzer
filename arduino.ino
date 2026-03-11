/*
 * Z-CAN Analyzer - Arduino Nano Firmware
 * MCP2515 CAN controller (e.g. via SPI)
 *
 * Serial <-> CAN bridge:
 * 1) CAN Rx -> Serial Tx: Forward received CAN frames to Android app
 * 2) Serial Rx -> CAN Tx: Transmit frames from Android app to CAN bus
 *
 * Packet format (16 bytes):
 * [0]   : 0xAA (start)
 * [1]   : ID type (0x01: Standard, 0x02: Extended, 0x00 from app = Standard)
 * [2-5] : CAN ID (4 bytes, big endian)
 * [6]   : DLC (1-8)
 * [7-14]: Data (8 bytes)
 * [15]  : 0xBB (end)
 */

#include <mcp_can.h>
#include <SPI.h>

#define CAN0_INT 2
#define CAN0_CS  10
MCP_CAN CAN0(CAN0_CS);

// Serial RX buffer for Android -> CAN
#define SERIAL_BUF_SIZE 32
uint8_t serialBuf[SERIAL_BUF_SIZE];
uint8_t serialIdx = 0;

// CAN Rx
long unsigned int rxId;
unsigned char len = 0;
unsigned char rxBuf[8];

void setup() {
  Serial.begin(115200);

  if (CAN0.begin(MCP_ANY, CAN_500KBPS, MCP_8MHZ) != CAN_OK) {
    while (1) { ; }
  }

  CAN0.init_Mask(0, 0, 0x00000000);
  CAN0.init_Mask(1, 0, 0x00000000);
  for (byte i = 0; i < 6; i++) {
    CAN0.init_Filt(i, 0, 0x00000000);
  }

  CAN0.setMode(MCP_NORMAL);
  pinMode(CAN0_INT, INPUT);
  delay(100);
}

void loop() {
  // 1) Serial Rx -> CAN Tx: Forward Android transmit to CAN bus
  processSerialToCan();

  // 2) CAN Rx -> Serial Tx: Forward CAN bus to Android
  processCanToSerial();
}

void processSerialToCan() {
  while (Serial.available() > 0) {
    uint8_t b = Serial.read();

    if (serialIdx == 0 && b != 0xAA) {
      continue;  // Wait for start byte
    }

    serialBuf[serialIdx++] = b;

    if (serialIdx >= 16) {
      serialIdx = 0;

      if (serialBuf[0] == 0xAA && serialBuf[15] == 0xBB) {
        uint8_t idType = serialBuf[1];
        if (idType == 0) idType = 1;  // 0 from app -> Standard

        uint32_t canId = (uint32_t)serialBuf[2] << 24 |
                         (uint32_t)serialBuf[3] << 16 |
                         (uint32_t)serialBuf[4] << 8  |
                         (uint32_t)serialBuf[5];

        uint8_t dlc = serialBuf[6];
        if (dlc < 1) dlc = 1;
        if (dlc > 8) dlc = 8;

        uint8_t ext = (idType == 2) ? 1 : 0;
        byte result = CAN0.sendMsgBuf(canId, ext, dlc, &serialBuf[7]);

        (void)result;  // Optionally handle send failure
      }
    } else if (serialIdx >= SERIAL_BUF_SIZE) {
      serialIdx = 0;
    }
  }
}

void processCanToSerial() {
  while (!digitalRead(CAN0_INT)) {
    if (CAN0.readMsgBuf(&rxId, &len, rxBuf) != CAN_OK) continue;

    Serial.write(0xAA);

    if ((rxId & 0x80000000) == 0x80000000) {
      Serial.write(0x02);
      rxId &= 0x1FFFFFFF;
    } else {
      Serial.write(0x01);
    }

    Serial.write((uint8_t)(rxId >> 24));
    Serial.write((uint8_t)(rxId >> 16));
    Serial.write((uint8_t)(rxId >> 8));
    Serial.write((uint8_t)(rxId & 0xFF));

    Serial.write(len);

    for (byte i = 0; i < 8; i++) {
      Serial.write((i < len) ? rxBuf[i] : 0x00);
    }

    Serial.write(0xBB);
  }
}
