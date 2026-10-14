package com.horsebreeder;

/**
 * AGENT NOTES —— 唯一的 main 源集类，只对外暴露 MOD_ID 与 LOGGER。
 *
 * <p>本 mod <b>不存在服务端逻辑</b>：所有行为都在 {@code client} 源集里，
 * 也因此 {@code HorseBreederClient#controller()} 只能在客户端调用。
 * 不要在这里加任何服务端逻辑，否则会变成隐式跨端副作用。
 */
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HorseBreeder {
    public static final String MOD_ID = "horsebreeder";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private HorseBreeder() {
    }
}
