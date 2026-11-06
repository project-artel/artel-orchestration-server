// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0
// UNPLACED: no scene object proven to host this type
// created by: Combat.Enemies.EnemyPoolController.enemyDataContainer

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Combat.Enemies
{
    class Enemy : MonoBehaviour
    {
        [InspectorCallable]
        // reached from: SwordEnemy.Attack, Enemy.Attack, BossEnemy.Attack
        // confidence derived
        void Attack(float p0)
        {
            Enemy.Animator.Attack();                                   // IL_0006
        }

        [UnityLifecycle]
        // reached from: SwordEnemy.Start, MagicEnemy.Start, Enemy.Start, BossEnemy.Start
        // confidence derived
        void Start()
        {
            Enemy.Animator = this.GetComponent();                      // IL_0007
            Enemy.turnTime = TurnBattleSystem.TurnTime;                // IL_0012
        }

        [InspectorCallable]
        // reached from: EnemyTestManager.PlayTurn, Enemy.PlayTurnAction
        // called by: Battle.Turns.EnemyTurn.OnStart, Combat.Enemies.EnemyTestManager.PlayTurn
        // confidence derived/verified
        void PlayTurnAction(float p0)
        {
            // path: EnemyTestManager.PlayTurn -> Enemy.PlayTurnAction
            if (EnemyTestManager.enemies.GetEnumerator().MoveNext() != 0)
            {
                MakeActionDecision();                                      // IL_0008
            }

            // path: Enemy.PlayTurnAction
            MakeActionDecision();                                      // IL_0008
        }

        [UnityLifecycle]
        // reached from: EnemyPoolController.Start
        // confidence derived
        void InitEnemyData(EnemyData p0)
        {
            // unresolved condition (subject lost): i < EnemyPoolController.enemyDataContainer.GetGearNum()
            // unresolved condition (subject lost): (EnemyData.id / 3) == EnemyPoolController.stagePosition
            // unresolved condition (subject lost): i < num
            // unresolved condition (subject lost): (EnemyData.id / 3) != EnemyPoolController.stagePosition
            if ((((EnemyData.id > 11) && (EnemyPoolController.stagePosition == 4))))
            {
                Enemy.id = EnemyData.id;                                   // IL_0007
                Enemy.MaxHp = EnemyData.maxHp;                             // IL_0013
                Enemy.Hp = Enemy.MaxHp;                                    // IL_001F
                Enemy.moveDistance = EnemyData.moveDistance;               // IL_002B
                Enemy.attackRange = EnemyData.attackRange;                 // IL_0037
                Enemy.Damage = EnemyData.damage;                           // IL_0043
                Enemy.enemyType = EnemyData.type;                          // IL_004F
                UpdateIndicator();                                         // IL_0055
            }
        }

        [UnityLifecycle]
        [InspectorCallable]
        // reached from: EnemyPoolController.Start, Enemy.TakeHit, Enemy.UpdateIndicator, SpellObj.OnTriggerEnter2D
        // called by: Combat.Enemies.Enemy.InitEnemyData, Combat.Enemies.Enemy.TakeHit, Combat.Spells.SpellObj.OnTriggerEnter2D
        // confidence derived/verified
        void UpdateIndicator()
        {
            // path: EnemyPoolController.Start -> EnemyPoolController.InitPool -> EnemyPoolController.MakeObjects -> Enemy.InitEnemyData -> Enemy.UpdateIndicator
            // unresolved condition (subject lost): i < EnemyPoolController.enemyDataContainer.GetGearNum()
            // unresolved condition (subject lost): (EnemyData.id / 3) == EnemyPoolController.stagePosition
            // unresolved condition (subject lost): i < num
            // unresolved condition (subject lost): (EnemyData.id / 3) != EnemyPoolController.stagePosition
            if ((((EnemyData.id > 11) && (EnemyPoolController.stagePosition == 4))))
            {
                Enemy.HpText.text = Int32.ToString(), true;                // IL_0012
            }

            // path: Enemy.TakeHit -> Enemy.UpdateIndicator
            if (Enemy.Hp > 0)
            {
                Enemy.HpText.text = Int32.ToString(), true;                // IL_0012
            }

            // path: Enemy.UpdateIndicator
            Enemy.HpText.text = Int32.ToString(), true;                // IL_0012

            // path: SpellObj.OnTriggerEnter2D -> Enemy.TakeHit -> Enemy.UpdateIndicator
            if ((collision.CompareTag("Enemy") != 0) && (collision.CompareTag(SpellObj.target.gameObject.tag) != 0) && (Enemy.Hp > 0))
            {
                Enemy.HpText.text = Int32.ToString(), true;                // IL_0012
            }
        }

        [UnityLifecycle]
        [InspectorCallable]
        // reached from: Enemy.TakeHit, SpellObj.OnTriggerEnter2D
        // called by: Combat.Spells.SpellObj.OnTriggerEnter2D
        // confidence derived/partial/verified; gaps: callee-condition-not-composed
        void TakeHit(int p0)
        {
            // path: Enemy.TakeHit
            Enemy.Hp = (Enemy.Hp - damage);                            // IL_0009

            // path: SpellObj.OnTriggerEnter2D -> Enemy.TakeHit
            if ((collision.CompareTag("Enemy") != 0) && (collision.CompareTag(SpellObj.target.gameObject.tag) != 0))
            {
                Enemy.Hp = (Enemy.Hp - damage);                            // IL_0009
            }

            // path: Enemy.TakeHit | SpellObj.OnTriggerEnter2D -> Enemy.TakeHit
            if (Enemy.Hp <= 0)
            {
                Death();                                                   // IL_0018
            }

            // path: Enemy.TakeHit | SpellObj.OnTriggerEnter2D -> Enemy.TakeHit
            if (Enemy.Hp > 0)
            {
                Enemy.Animator.TakeHit();                                  // IL_0024
                UpdateIndicator();                                         // IL_002A
            }
        }

        [UnityLifecycle]
        [InspectorCallable]
        // reached from: Enemy.TakeHit, SpellObj.OnTriggerEnter2D
        // called by: Combat.Spells.SpellObj.OnTriggerEnter2D
        // confidence derived
        void Death()
        {
            // path: Enemy.TakeHit -> Enemy.Death
            if (Enemy.Hp <= 0)
            {
                Enemy.Animator.Death();                                    // IL_0006
                StartCoroutine(DeathCounter());                            // IL_000D
            }

            // path: SpellObj.OnTriggerEnter2D -> Enemy.TakeHit -> Enemy.Death
            if ((collision.CompareTag("Enemy") != 0) && (collision.CompareTag(SpellObj.target.gameObject.tag) != 0) && (Enemy.Hp <= 0))
            {
                Enemy.Animator.Death();                                    // IL_0006
                StartCoroutine(DeathCounter());                            // IL_000D
            }
        }

        [UnityLifecycle]
        [InspectorCallable]
        // reached from: Enemy.TakeHit, SpellObj.OnTriggerEnter2D
        // called by: Combat.Spells.SpellObj.OnTriggerEnter2D
        // confidence derived
        IEnumerator DeathCounter()
        {
            // path: Enemy.TakeHit -> Enemy.Death -> Enemy.DeathCounter
            if (Enemy.Hp <= 0)
            {
                // no observed statements
            }

            // path: SpellObj.OnTriggerEnter2D -> Enemy.TakeHit -> Enemy.Death -> Enemy.DeathCounter
            if ((collision.CompareTag("Enemy") != 0) && (collision.CompareTag(SpellObj.target.gameObject.tag) != 0) && (Enemy.Hp <= 0))
            {
                // no observed statements
            }

            // path: Enemy.TakeHit -> Enemy.Death -> Enemy.DeathCounter -> Enemy/<DeathCounter>d__25.MoveNext
            if (Enemy.Hp <= 0)
            {
                Component.gameObject.SetActive(false);                     // IL_0045
            }

            // path: SpellObj.OnTriggerEnter2D -> Enemy.TakeHit -> Enemy.Death -> Enemy.DeathCounter -> Enemy/<DeathCounter>d__25.MoveNext
            if ((collision.CompareTag("Enemy") != 0) && (collision.CompareTag(SpellObj.target.gameObject.tag) != 0) && (Enemy.Hp <= 0))
            {
                Component.gameObject.SetActive(false);                     // IL_0045
            }
        }

        [UnityLifecycle]
        // confidence verified
        void Awake()
        {
            InitIndicators();                                          // IL_0001
            InitEnemyActions();                                        // IL_0007
        }

        [UnityLifecycle]
        // reached from: Enemy.Awake
        // confidence derived
        void InitEnemyActions()
        {
            new EnemyAttackAction();                                   // IL_0007
            new EnemyMoveAction();                                     // IL_0018
        }

        [UnityLifecycle]
        // reached from: Enemy.Awake
        // confidence derived
        EnemyMoveAction(Enemy p0)
        {
            new EnemyAction();                                         // IL_0002
        }

        [UnityLifecycle]
        // reached from: Enemy.Awake
        // confidence derived
        EnemyAction(Enemy p0)
        {
            EnemyAction.Enemy = enemy;                                 // IL_0008
        }

        [UnityLifecycle]
        // reached from: Enemy.Awake
        // confidence derived
        EnemyAttackAction(Enemy p0)
        {
            new EnemyAction();                                         // IL_0002
        }

        [UnityLifecycle]
        // reached from: Enemy.Awake
        // confidence derived
        void InitIndicators()
        {
            Enemy.HpText = this.gameObject.GetComponentInChildren();   // IL_000C
            Enemy.HpText.text = Int32.ToString(), true;                // IL_0023
        }
    }
}
