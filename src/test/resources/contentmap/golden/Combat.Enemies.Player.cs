// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Combat.Enemies
{
    class Player : MonoBehaviour
    {
        [UnityLifecycle]
        [InspectorCallable]
        // reached from: SwordEnemy.Attack, BossEnemy.Attack, EnemyProjectile.OnTriggerEnter2D, Player.TakeHit, SpellObj.OnTriggerEnter2D
        // called by: Combat.Enemies.BossEnemy.Attack, Combat.Enemies.EnemyProjectile.OnTriggerEnter2D, Combat.Enemies.SwordEnemy.Attack, Combat.Spells.SpellObj.OnTriggerEnter2D
        // confidence derived/partial/verified; gaps: callee-condition-not-composed
        void TakeHit(int p0)
        {
            // path: Player.TakeHit | SwordEnemy.Attack -> Player.TakeHit
            // unresolved condition (subject lost): distanceToPlayer < Enemy.attackRange
            Player.Hp = (Player.Hp - damage);                          // IL_0009

            // path: EnemyProjectile.OnTriggerEnter2D -> Player.TakeHit
            if (collision.CompareTag("Me") != 0)
            {
                Player.Hp = (Player.Hp - damage);                          // IL_0009
            }

            // path: SpellObj.OnTriggerEnter2D -> Player.TakeHit
            if ((collision.CompareTag("Enemy") == 0) && (collision.CompareTag(SpellObj.target.gameObject.tag) != 0))
            {
                Player.Hp = (Player.Hp - damage);                          // IL_0009
            }

            // path: SwordEnemy.Attack -> Player.TakeHit
            if (Player.PlayerInt().Hp <= 0)
            {
                Death();                                                   // IL_0018
            }

            // path: Player.TakeHit | SpellObj.OnTriggerEnter2D -> Player.TakeHit
            if (Player.Hp <= 0)
            {
                Death();                                                   // IL_0018
            }

            // path: SwordEnemy.Attack -> Player.TakeHit
            if ((Enemy.Damage > 0) && (Player.PlayerInt().Hp > 0))
            {
                Player.animator.SetTrigger("GetHit");                      // IL_002D
                UpdateIndicator();                                         // IL_0033
            }

            // path: Player.TakeHit | SpellObj.OnTriggerEnter2D -> Player.TakeHit
            if ((damage > 0) && (Player.Hp > 0))
            {
                Player.animator.SetTrigger("GetHit");                      // IL_002D
                UpdateIndicator();                                         // IL_0033
            }

            // path: EnemyProjectile.OnTriggerEnter2D -> Player.TakeHit
            if ((EnemyProjectile.damage > 0) && (Player.PlayerInt().Hp > 0))
            {
                Player.animator.SetTrigger("GetHit");                      // IL_002D
                UpdateIndicator();                                         // IL_0033
            }

            // path: SwordEnemy.Attack -> Player.TakeHit
            if ((Enemy.Damage <= 0) && (Player.PlayerInt().Hp > 0))
            {
                UpdateIndicator();                                         // IL_003A
            }

            // path: Player.TakeHit | SpellObj.OnTriggerEnter2D -> Player.TakeHit
            if ((damage <= 0) && (Player.Hp > 0))
            {
                UpdateIndicator();                                         // IL_003A
            }

            // path: EnemyProjectile.OnTriggerEnter2D -> Player.TakeHit
            if ((EnemyProjectile.damage <= 0) && (Player.PlayerInt().Hp > 0))
            {
                UpdateIndicator();                                         // IL_003A
            }
        }

        [UnityLifecycle]
        [InspectorCallable]
        // reached from: SwordEnemy.Attack, BossEnemy.Attack, Player.TakeHit, Player.UpdateIndicator, EnemyProjectile.OnTriggerEnter2D, SpellObj.OnTriggerEnter2D
        // called by: Combat.Enemies.BossEnemy.Attack, Combat.Enemies.EnemyProjectile.OnTriggerEnter2D, Combat.Enemies.Player.TakeHit, Combat.Enemies.SwordEnemy.Attack, Combat.Spells.SpellObj.OnTriggerEnter2D
        // confidence derived/verified
        void UpdateIndicator()
        {
            // path: SwordEnemy.Attack -> Player.TakeHit -> Player.UpdateIndicator
            // unresolved condition (subject lost): distanceToPlayer < Enemy.attackRange
            if ((((damage > 0) && (Player.Hp > 0)) || ((damage <= 0) && (Player.Hp > 0))))
            {
                Player.HpText.text = Int32.ToString(), true;               // IL_0012
            }

            // path: Player.TakeHit -> Player.UpdateIndicator
            if (((damage > 0) && (Player.Hp > 0)) || ((damage <= 0) && (Player.Hp > 0)))
            {
                Player.HpText.text = Int32.ToString(), true;               // IL_0012
            }

            // path: Player.UpdateIndicator
            Player.HpText.text = Int32.ToString(), true;               // IL_0012

            // path: EnemyProjectile.OnTriggerEnter2D -> Player.TakeHit -> Player.UpdateIndicator
            if ((collision.CompareTag("Me") != 0) && (((damage > 0) && (Player.Hp > 0)) || ((damage <= 0) && (Player.Hp > 0))))
            {
                Player.HpText.text = Int32.ToString(), true;               // IL_0012
            }

            // path: SpellObj.OnTriggerEnter2D -> Player.TakeHit -> Player.UpdateIndicator
            if ((collision.CompareTag("Enemy") == 0) && (collision.CompareTag(SpellObj.target.gameObject.tag) != 0) && (((damage > 0) && (Player.Hp > 0)) || ((damage <= 0) && (Player.Hp > 0))))
            {
                Player.HpText.text = Int32.ToString(), true;               // IL_0012
            }
        }

        [UnityLifecycle]
        [InspectorCallable]
        // reached from: SwordEnemy.Attack, BossEnemy.Attack, Player.TakeHit, EnemyProjectile.OnTriggerEnter2D, SpellObj.OnTriggerEnter2D
        // called by: Combat.Enemies.BossEnemy.Attack, Combat.Enemies.EnemyProjectile.OnTriggerEnter2D, Combat.Enemies.SwordEnemy.Attack, Combat.Spells.SpellObj.OnTriggerEnter2D
        // confidence derived
        void Death()
        {
            // path: SwordEnemy.Attack -> Player.TakeHit -> Player.Death
            // unresolved condition (subject lost): distanceToPlayer < Enemy.attackRange
            if ((Player.Hp <= 0))
            {
                Player.animator.SetTrigger("Death");                       // IL_000B
                SceneManager.LoadScene("GameOverScene");                   // IL_0015
            }

            // path: Player.TakeHit -> Player.Death
            if (Player.Hp <= 0)
            {
                Player.animator.SetTrigger("Death");                       // IL_000B
                SceneManager.LoadScene("GameOverScene");                   // IL_0015
            }

            // path: EnemyProjectile.OnTriggerEnter2D -> Player.TakeHit -> Player.Death
            if ((collision.CompareTag("Me") != 0) && (Player.Hp <= 0))
            {
                Player.animator.SetTrigger("Death");                       // IL_000B
                SceneManager.LoadScene("GameOverScene");                   // IL_0015
            }

            // path: SpellObj.OnTriggerEnter2D -> Player.TakeHit -> Player.Death
            if ((collision.CompareTag("Enemy") == 0) && (collision.CompareTag(SpellObj.target.gameObject.tag) != 0) && (Player.Hp <= 0))
            {
                Player.animator.SetTrigger("Death");                       // IL_000B
                SceneManager.LoadScene("GameOverScene");                   // IL_0015
            }
        }

        [InspectorCallable]
        // called by: Combat.UI.CombineZone/<CastSpell>d__18.MoveNext
        // confidence verified
        void AttackAnima()
        {
            Player.animator.SetTrigger("Attack");                      // IL_000B
        }

        [UnityLifecycle]
        // confidence verified
        void Awake()
        {
            InitIndicators();                                          // IL_0001
            Player.animator = this.GetComponent();                     // IL_000D
            Player._instance = this;                                   // IL_0013
        }

        [UnityLifecycle]
        // reached from: Player.Awake
        // confidence derived
        void InitIndicators()
        {
            Player.HpText = this.gameObject.GetComponentInChildren();  // IL_000C
            Player.HpText.text = Int32.ToString(), true;               // IL_0023
        }
    }
}
