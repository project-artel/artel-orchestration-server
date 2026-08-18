// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0
// UNPLACED: no scene object proven to host this type
// created by: Scenes.GameClearController.magicCard, Scenes.GameClearController.spellCard, Cards.CardManager.cardPrefab

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Cards
{
    class Order : MonoBehaviour
    {
        [InspectorCallable]
        // confidence verified
        void SetMostFrontOrder(bool p0)
        {
            Order.SetOrder();                                          // IL_000E
        }

        [InspectorCallable]
        // reached from: Order.SetOriginOrder, CardManager.AddCard
        // called by: Battle.Turns.PlayerTurn.OnStart, Cards.CardManager.SetOriginOrder
        // confidence partial/verified; gaps: unread-condition
        void SetOriginOrder(int p0)
        {
            // path: Order.SetOriginOrder
            Order.originOrder = originOrder;                           // IL_0002
            SetOrder();                                                // IL_0009

            // path: CardManager.AddCard -> CardManager.SetOriginOrder -> Order.SetOriginOrder
            // unresolved condition (subject lost): i < CardManager.myCards.Count
            if ((/* unknown */))
            {
                Order.originOrder = originOrder;                           // IL_0002
                SetOrder();                                                // IL_0009
            }
        }
    }
}
