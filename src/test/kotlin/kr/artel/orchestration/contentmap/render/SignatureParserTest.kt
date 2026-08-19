package kr.artel.orchestration.contentmap.render

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SignatureParserTest {

    @Test
    fun `일반 메서드 시그니처를 반환형 선언타입 이름 파라미터로 나눈다`() {
        val p = parseSignature("System.Void Combat.Enemies.BattleWaveController::StartWave(System.Int32)")

        assertThat(p.returnType).isEqualTo("System.Void")
        assertThat(p.declaringType).isEqualTo("Combat.Enemies.BattleWaveController")
        assertThat(p.name).isEqualTo("StartWave")
        assertThat(p.params).containsExactly("System.Int32")
    }

    @Test
    fun `형태가 안 맞는 시그니처는 이름 자리에 원문을 채운 껍데기를 낸다`() {
        val p = parseSignature("garbage")

        assertThat(p.returnType).isEqualTo("void")
        assertThat(p.declaringType).isEmpty()
        assertThat(p.name).isEqualTo("garbage")
        assertThat(p.params).isEmpty()
    }

    @Test
    fun `상태 머신 서명을 상태머신으로 인식한다`() {
        assertThat(isGeneratedSignature("System.Boolean Combat.Enemies.BattleWaveController/<WaveEndSensor>d__6::MoveNext()")).isTrue()
        assertThat(isGeneratedSignature("System.Void Combat.Enemies.BattleWaveController::StartWave(System.Int32)")).isFalse()
    }

    @Test
    fun `declShort 는 선언타입 마지막 세그먼트와 메서드 이름을 잇는다`() {
        assertThat(declShort("System.Void Combat.Enemies.BattleWaveController::StartWave(System.Int32)"))
            .isEqualTo("BattleWaveController.StartWave")
    }

    @Test
    fun `중첩 제네릭도 최상위 쉼표에서만 나눈다`() {
        assertThat(splitGenericArgs("System.String,System.Collections.Generic.List`1<System.Int32>"))
            .containsExactly("System.String", "System.Collections.Generic.List`1<System.Int32>")
    }

    @Test
    fun `shortType 은 원시타입을 C# 키워드로 제네릭 인자를 재귀 축약한다`() {
        assertThat(shortType("System.Void")).isEqualTo("void")
        assertThat(shortType("System.Int32")).isEqualTo("int")
        assertThat(shortType("System.Collections.IEnumerator")).isEqualTo("IEnumerator")
        assertThat(shortType("System.Collections.Generic.List`1<System.Int32>")).isEqualTo("List<int>")
        assertThat(shortType("Combat.Enemies.Player")).isEqualTo("Player")
        assertThat(shortType(null)).isEqualTo("var")
    }

    @Test
    fun `methodDecl 은 생성자 getter setter 일반 메서드를 구분해서 낸다`() {
        assertThat(methodDecl("System.Void Cards.Card::.ctor(UnityEngine.Vector3)")).isEqualTo("Card(Vector3 p0)")
        assertThat(methodDecl("System.Int32 Combat.Enemies.Player::get_Hp()")).isEqualTo("int Hp { get; }")
        assertThat(methodDecl("System.Void Combat.Enemies.Player::set_Hp(System.Int32)")).isEqualTo("int Hp { set; }")
        assertThat(methodDecl("System.Collections.IEnumerator Combat.Enemies.BattleWaveController::WaveEndSensor()"))
            .isEqualTo("IEnumerator WaveEndSensor()")
    }
}
