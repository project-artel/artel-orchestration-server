// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Battle.Turns
{
    class TurnBattleSystem : MonoBehaviour
    {
        [InspectorCallable]
        // called by: Battle.Turns.EnemyTurn.OnStart
        // confidence verified
        void StartEnemyTurnCounter()
        {
            StartCoroutine(EnemyTurnCounter());                        // IL_0002
        }

        [InspectorCallable]
        // reached from: TurnBattleSystem.StartEnemyTurnCounter
        // called by: Battle.Turns.EnemyTurn.OnStart
        // confidence derived
        IEnumerator EnemyTurnCounter()
        {
            TurnBattleSystem.ChangeTurn(TurnBattleSystem.PlayerTurn);  // IL_004E
        }

        [InspectorCallable]
        // reached from: TurnBattleSystem.StartEnemyTurnCounter, TurnBattleSystem.TurnEndButton
        // called by: Battle.Turns.EnemyTurn.OnStart
        // confidence derived
        void ChangeTurn(Turn p0)
        {
            // path: TurnBattleSystem.StartEnemyTurnCounter -> TurnBattleSystem.EnemyTurnCounter -> TurnBattleSystem/<EnemyTurnCounter>d__13.MoveNext -> TurnBattleSystem.ChangeTurn
            TurnBattleSystem.currentTurn = turn;                       // IL_000D

            // path: TurnBattleSystem.TurnEndButton -> TurnBattleSystem.ChangeTurn
            if (TurnBattleSystem.currentTurn == TurnBattleSystem.PlayerTurn)
            {
                TurnBattleSystem.currentTurn = turn;                       // IL_000D
            }
        }

        [UnityEvent(wired: "DebugCanvas/TurnEndButton")]
        // confidence verified
        void TurnEndButton()
        {
            if (TurnBattleSystem.currentTurn == TurnBattleSystem.PlayerTurn)
            {
                ChangeTurn(TurnBattleSystem.EnemyTurn);                    // IL_0013
            }
        }

        [UnityLifecycle]
        // confidence verified
        void Start()
        {
            TurnBattleSystem.currentTurn = TurnBattleSystem.PlayerTurn; // IL_0006
        }

        [UnityLifecycle]
        // confidence partial/verified; gaps: singleton-plumbing
        void Awake()
        {
            if (TurnBattleSystem.Instance == null)
            {
                TurnBattleSystem.Instance = this;                          // IL_000E
            }

            if (TurnBattleSystem.Instance != null)
            {
                Destroy(this);                                             // IL_0016
            }

            new PlayerTurn();                                          // IL_001B
            TurnBattleSystem.PlayerTurn = /* ? */;                     // IL_0020
            new EnemyTurn();                                           // IL_0025
            TurnBattleSystem.EnemyTurn = /* ? */;                      // IL_002A
        }

        [UnityLifecycle]
        // reached from: TurnBattleSystem.Awake
        // confidence derived
        EnemyTurn()
        {
            new Turn();                                                // IL_0001
        }

        [UnityLifecycle]
        // reached from: TurnBattleSystem.Awake
        // confidence derived
        PlayerTurn()
        {
            new Turn();                                                // IL_0001
        }
    }
}
