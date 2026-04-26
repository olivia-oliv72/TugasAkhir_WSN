import com.virtenio.driver.device.ADT7410;
import com.virtenio.driver.device.ADXL345;
import com.virtenio.driver.device.MPL115A2;
import com.virtenio.driver.device.SHT21;

import com.virtenio.driver.gpio.GPIO;
import com.virtenio.driver.gpio.NativeGPIO;

import com.virtenio.driver.spi.NativeSPI;
import com.virtenio.driver.spi.SPIException;
import com.virtenio.vm.Time;
import com.virtenio.driver.i2c.I2C;
import com.virtenio.driver.i2c.NativeI2C;

public class Sensor {

    // ===== SENSOR OBJECT =====
    private ADXL345 acclSensor;
    private ADT7410 tempSensor;
    private SHT21 humSensor;
    private MPL115A2 pressSensor;

    // ===== INTERFACE =====
    private NativeSPI spi;
    private NativeI2C i2c;
    private GPIO accelCs;
    private GPIO resetPin;
    private GPIO shutDownPin;

    private boolean initialized = false;

    // ===== INIT SEMUA SENSOR =====
    public void init() throws Exception {
        if (initialized) return;

        // ===== INIT SPI (ACCEL) =====
        accelCs = NativeGPIO.getInstance(20);
        spi = NativeSPI.getInstance(0);

        if (!spi.isOpened()) {
            spi.open(ADXL345.SPI_MODE, ADXL345.SPI_BIT_ORDER, ADXL345.SPI_MAX_SPEED);
        }

        acclSensor = new ADXL345(spi, accelCs);
        if (!acclSensor.isOpened()) {
            acclSensor.open();
            acclSensor.setDataFormat(ADXL345.DATA_FORMAT_RANGE_2G);
            acclSensor.setDataRate(ADXL345.DATA_RATE_100HZ);
            acclSensor.setPowerControl(ADXL345.POWER_CONTROL_MEASURE);
        }

        // ===== INIT I2C =====
        i2c = NativeI2C.getInstance(1);
        if (!i2c.isOpened()) {
            i2c.open(I2C.DATA_RATE_400);
        }

        // ===== TEMP =====
        tempSensor = new ADT7410(i2c, ADT7410.ADDR_0, null, null);
        if (!tempSensor.isOpened()) {
            tempSensor.open();
            tempSensor.setMode(ADT7410.CONFIG_MODE_CONTINUOUS);
        }

        // ===== HUM =====
        humSensor = new SHT21(i2c);
        if (!humSensor.isOpened()) {
            humSensor.open();
            humSensor.setResolution(SHT21.RESOLUTION_RH12_T14);
        }

        // ===== PRESSURE =====
        resetPin = NativeGPIO.getInstance(24);
        shutDownPin = NativeGPIO.getInstance(12);

        pressSensor = new MPL115A2(i2c, resetPin, shutDownPin);
        if (!pressSensor.isOpened()) {
            pressSensor.open();
            pressSensor.setReset(false);
            pressSensor.setShutdown(false);
        }

        initialized = true;
    }

    // ===== READ SEMUA SENSOR =====
    public String readAll() throws Exception {
        if (!initialized) {
            init();
        }

        // ===== ACCEL =====
        short[] accel = new short[3];
        acclSensor.getValuesRaw(accel, 0);

        // ===== TEMP =====
        float temp = tempSensor.getTemperatureCelsius();

        // ===== HUM =====
        humSensor.startRelativeHumidityConversion();
        Thread.sleep(SHT21.MAX_HUMIDITY_CONVERSION_TIME_R12);
        int rawRH = humSensor.getRelativeHumidityRaw();
        float hum = SHT21.convertRawRHToRHw(rawRH);

        // ===== PRESS =====
        pressSensor.startBothConversion();
        Thread.sleep(MPL115A2.BOTH_CONVERSION_TIME);
        int pRaw = pressSensor.getPressureRaw();
        int tRaw = pressSensor.getTemperatureRaw();
        float press = pressSensor.compensate(pRaw, tRaw);

        // ===== FORMAT OUTPUT =====
        return accel[0] + "," + accel[1] + "," + accel[2]
        	     + " " + ((int)(temp * 10) / 10.0)
        	     + " " + ((int)(hum * 10) / 10.0)
        	     + " " + ((int)(press * 10) / 10.0) 
        	     + " " + Time.currentTimeMillis();
    }
}