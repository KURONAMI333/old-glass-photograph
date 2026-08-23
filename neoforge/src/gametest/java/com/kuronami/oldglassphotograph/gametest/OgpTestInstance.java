package com.kuronami.oldglassphotograph.gametest;

import java.util.function.Consumer;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * 26.x の GameTest は「アノテーションの付いた static メソッド」ではなく、レジストリに載る
 * {@link GameTestInstance} が単位になった（{@code net.minecraft.gametest.framework.GameTest} と
 * NeoForge の {@code @GameTestHolder}/{@code @PrefixGameTestTemplate} は 26.2 に存在しない。
 * {@code PLAYBOOK_GAMETEST.md} / mod-066 の {@code mc-26.2} セルと同型）。
 *
 * <p>ここではテスト本体を {@code Consumer<GameTestHelper>} として受け取る薄い実装だけを置く。
 */
public class OgpTestInstance extends GameTestInstance {

    private final String name;
    private final Consumer<GameTestHelper> body;

    public OgpTestInstance(String name,
                            TestData<Holder<TestEnvironmentDefinition<?>>> info,
                            Consumer<GameTestHelper> body) {
        super(info);
        this.name = name;
        this.body = body;
    }

    @Override
    public void run(GameTestHelper helper) {
        this.body.accept(helper);
    }

    @Override
    public MapCodec<? extends GameTestInstance> codec() {
        return MapCodec.unit(this);
    }

    @Override
    protected MutableComponent typeDescription() {
        return Component.literal("old glass photograph test: " + this.name);
    }
}
