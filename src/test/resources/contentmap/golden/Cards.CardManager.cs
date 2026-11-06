// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Cards
{
    class CardManager : MonoBehaviour
    {
        [UnityLifecycle]
        [InspectorCallable]
        // reached from: CardManager.CardMouseUp, Card.OnMouseUp
        // called by: Cards.Card.OnMouseUp
        // confidence derived/partial/verified; gaps: composed-on-same-object
        void CardMouseUp()
        {
            // path: CardManager.CardMouseUp
            CardManager.isMyCardDrag = 0;                              // IL_0002

            // path: Card.OnMouseUp -> CardManager.CardMouseUp
            if (InteractionLock.IsLocked == 0)
            {
                CardManager.isMyCardDrag = 0;                              // IL_0002
            }

            // path: CardManager.CardMouseUp
            if ((CombineZone.spellCards.Count == 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0))
            {
                CardManager.pushArea1.GetComponent().GetCard(CardManager.selectCard.gameObject); // IL_0048
                Util.Qi;                                                   // IL_0063
                new Prs(CardManager.pushArea1.transform.position, Util.Qi, Prs.scale); // IL_0078
                CardManager.selectCard.MoveTransform(_, false, 0);         // IL_0083
            }

            // path: Card.OnMouseUp -> CardManager.CardMouseUp
            if ((InteractionLock.IsLocked == 0) && (CombineZone.spellCards.Count == 0) && (CardManager.Inst.selectCard.CompareTag("Spell") != 0) && (CardManager.Inst.onPushArea1 != 0))
            {
                CardManager.pushArea1.GetComponent().GetCard(CardManager.selectCard.gameObject); // IL_0048
                Util.Qi;                                                   // IL_0063
                new Prs(CardManager.pushArea1.transform.position, Util.Qi, Prs.scale); // IL_0078
                CardManager.selectCard.MoveTransform(_, false, 0);         // IL_0083
            }

            // path: CardManager.CardMouseUp
            if ((CombineZone.magicTypeCards.Count == 0) && (CardManager.selectCard.CompareTag("MagicType") != 0) && (CardManager.onPushArea2 != 0) && ((CardManager.onPushArea1 == 0) || ((CardManager.selectCard.CompareTag("Spell") == 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0))))
            {
                CardManager.pushArea2.GetComponent().GetCard(CardManager.selectCard.gameObject); // IL_00CA
                Util.Qi;                                                   // IL_00E5
                new Prs(CardManager.pushArea2.transform.position, Util.Qi, Prs.scale); // IL_00FA
                CardManager.selectCard.MoveTransform(_, false, 0);         // IL_0105
            }

            // path: Card.OnMouseUp -> CardManager.CardMouseUp
            if ((InteractionLock.IsLocked == 0) && (CombineZone.magicTypeCards.Count == 0) && (CardManager.Inst.selectCard.CompareTag("MagicType") != 0) && (CardManager.Inst.onPushArea2 != 0) && ((CardManager.Inst.onPushArea1 == 0) || ((CardManager.Inst.selectCard.CompareTag("Spell") == 0) && (CardManager.Inst.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.Inst.selectCard.CompareTag("Spell") != 0) && (CardManager.Inst.onPushArea1 != 0))))
            {
                CardManager.pushArea2.GetComponent().GetCard(CardManager.selectCard.gameObject); // IL_00CA
                Util.Qi;                                                   // IL_00E5
                new Prs(CardManager.pushArea2.transform.position, Util.Qi, Prs.scale); // IL_00FA
                CardManager.selectCard.MoveTransform(_, false, 0);         // IL_0105
            }

            // path: CardManager.CardMouseUp
            if ((CardManager.selectCard.CompareTag("Target") != 0) && (CardManager.onPushArea3 != 0) && (((CardManager.onPushArea2 == 0) && ((CardManager.onPushArea1 == 0) || ((CardManager.selectCard.CompareTag("Spell") == 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0)))) || ((CardManager.selectCard.CompareTag("MagicType") == 0) && (CardManager.onPushArea2 != 0) && ((CardManager.onPushArea1 == 0) || ((CardManager.selectCard.CompareTag("Spell") == 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0)))) || ((CombineZone.magicTypeCards.Count != 0) && (CardManager.selectCard.CompareTag("MagicType") != 0) && (CardManager.onPushArea2 != 0) && ((CardManager.onPushArea1 == 0) || ((CardManager.selectCard.CompareTag("Spell") == 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0))))))
            {
                CardManager.pushArea3.GetComponent().GetCard(CardManager.selectCard.gameObject); // IL_013B
                Util.Qi;                                                   // IL_0156
                new Prs(CardManager.pushArea3.transform.position, Util.Qi, Prs.scale); // IL_016B
                CardManager.selectCard.MoveTransform(_, false, 0);         // IL_0176
            }

            // path: Card.OnMouseUp -> CardManager.CardMouseUp
            if ((InteractionLock.IsLocked == 0) && (CardManager.Inst.selectCard.CompareTag("Target") != 0) && (CardManager.Inst.onPushArea3 != 0) && (((CardManager.Inst.onPushArea2 == 0) && ((CardManager.Inst.onPushArea1 == 0) || ((CardManager.Inst.selectCard.CompareTag("Spell") == 0) && (CardManager.Inst.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.Inst.selectCard.CompareTag("Spell") != 0) && (CardManager.Inst.onPushArea1 != 0)))) || ((CardManager.Inst.selectCard.CompareTag("MagicType") == 0) && (CardManager.Inst.onPushArea2 != 0) && ((CardManager.Inst.onPushArea1 == 0) || ((CardManager.Inst.selectCard.CompareTag("Spell") == 0) && (CardManager.Inst.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.Inst.selectCard.CompareTag("Spell") != 0) && (CardManager.Inst.onPushArea1 != 0)))) || ((CombineZone.magicTypeCards.Count != 0) && (CardManager.Inst.selectCard.CompareTag("MagicType") != 0) && (CardManager.Inst.onPushArea2 != 0) && ((CardManager.Inst.onPushArea1 == 0) || ((CardManager.Inst.selectCard.CompareTag("Spell") == 0) && (CardManager.Inst.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.Inst.selectCard.CompareTag("Spell") != 0) && (CardManager.Inst.onPushArea1 != 0))))))
            {
                CardManager.pushArea3.GetComponent().GetCard(CardManager.selectCard.gameObject); // IL_013B
                Util.Qi;                                                   // IL_0156
                new Prs(CardManager.pushArea3.transform.position, Util.Qi, Prs.scale); // IL_016B
                CardManager.selectCard.MoveTransform(_, false, 0);         // IL_0176
            }

            // path: CardManager.CardMouseUp
            if (((CardManager.onPushArea3 == 0) && (((CardManager.onPushArea2 == 0) && ((CardManager.onPushArea1 == 0) || ((CardManager.selectCard.CompareTag("Spell") == 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0)))) || ((CardManager.selectCard.CompareTag("MagicType") == 0) && (CardManager.onPushArea2 != 0) && ((CardManager.onPushArea1 == 0) || ((CardManager.selectCard.CompareTag("Spell") == 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0)))) || ((CombineZone.magicTypeCards.Count != 0) && (CardManager.selectCard.CompareTag("MagicType") != 0) && (CardManager.onPushArea2 != 0) && ((CardManager.onPushArea1 == 0) || ((CardManager.selectCard.CompareTag("Spell") == 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0)))))) || ((CardManager.selectCard.CompareTag("Target") == 0) && (CardManager.onPushArea3 != 0) && (((CardManager.onPushArea2 == 0) && ((CardManager.onPushArea1 == 0) || ((CardManager.selectCard.CompareTag("Spell") == 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0)))) || ((CardManager.selectCard.CompareTag("MagicType") == 0) && (CardManager.onPushArea2 != 0) && ((CardManager.onPushArea1 == 0) || ((CardManager.selectCard.CompareTag("Spell") == 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0)))) || ((CombineZone.magicTypeCards.Count != 0) && (CardManager.selectCard.CompareTag("MagicType") != 0) && (CardManager.onPushArea2 != 0) && ((CardManager.onPushArea1 == 0) || ((CardManager.selectCard.CompareTag("Spell") == 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0)))))))
            {
                CardManager.selectCard.MoveTransform(Card.originPrs, false, 0); // IL_01D5
            }

            // path: Card.OnMouseUp -> CardManager.CardMouseUp
            if ((InteractionLock.IsLocked == 0) && (((CardManager.Inst.onPushArea3 == 0) && (((CardManager.Inst.onPushArea2 == 0) && ((CardManager.Inst.onPushArea1 == 0) || ((CardManager.Inst.selectCard.CompareTag("Spell") == 0) && (CardManager.Inst.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.Inst.selectCard.CompareTag("Spell") != 0) && (CardManager.Inst.onPushArea1 != 0)))) || ((CardManager.Inst.selectCard.CompareTag("MagicType") == 0) && (CardManager.Inst.onPushArea2 != 0) && ((CardManager.Inst.onPushArea1 == 0) || ((CardManager.Inst.selectCard.CompareTag("Spell") == 0) && (CardManager.Inst.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.Inst.selectCard.CompareTag("Spell") != 0) && (CardManager.Inst.onPushArea1 != 0)))) || ((CombineZone.magicTypeCards.Count != 0) && (CardManager.Inst.selectCard.CompareTag("MagicType") != 0) && (CardManager.Inst.onPushArea2 != 0) && ((CardManager.Inst.onPushArea1 == 0) || ((CardManager.Inst.selectCard.CompareTag("Spell") == 0) && (CardManager.Inst.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.Inst.selectCard.CompareTag("Spell") != 0) && (CardManager.Inst.onPushArea1 != 0)))))) || ((CardManager.Inst.selectCard.CompareTag("Target") == 0) && (CardManager.Inst.onPushArea3 != 0) && (((CardManager.Inst.onPushArea2 == 0) && ((CardManager.Inst.onPushArea1 == 0) || ((CardManager.Inst.selectCard.CompareTag("Spell") == 0) && (CardManager.Inst.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.Inst.selectCard.CompareTag("Spell") != 0) && (CardManager.Inst.onPushArea1 != 0)))) || ((CardManager.Inst.selectCard.CompareTag("MagicType") == 0) && (CardManager.Inst.onPushArea2 != 0) && ((CardManager.Inst.onPushArea1 == 0) || ((CardManager.Inst.selectCard.CompareTag("Spell") == 0) && (CardManager.Inst.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.Inst.selectCard.CompareTag("Spell") != 0) && (CardManager.Inst.onPushArea1 != 0)))) || ((CombineZone.magicTypeCards.Count != 0) && (CardManager.Inst.selectCard.CompareTag("MagicType") != 0) && (CardManager.Inst.onPushArea2 != 0) && ((CardManager.Inst.onPushArea1 == 0) || ((CardManager.Inst.selectCard.CompareTag("Spell") == 0) && (CardManager.Inst.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.Inst.selectCard.CompareTag("Spell") != 0) && (CardManager.Inst.onPushArea1 != 0))))))))
            {
                CardManager.selectCard.MoveTransform(Card.originPrs, false, 0); // IL_01D5
            }
        }

        [UnityLifecycle]
        [InspectorCallable]
        // reached from: CardManager.CardMouseUp, CardManager.CardAlignment, CardManager.AddCard, CardManager.Update
        // called by: Cards.Card.OnMouseUp, Cards.CardManager.AddCard, Combat.UI.CombineZone.ClearDropZone
        // confidence derived
        Prs(Vector3 p0, Quaternion p1, Vector3 p2)
        {
            // path: CardManager.CardMouseUp -> Prs..ctor
            if (((CombineZone.spellCards.Count == 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.magicTypeCards.Count == 0) && (CardManager.selectCard.CompareTag("MagicType") != 0) && (CardManager.onPushArea2 != 0) && ((CardManager.onPushArea1 == 0) || ((CardManager.selectCard.CompareTag("Spell") == 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0)))) || ((CardManager.selectCard.CompareTag("Target") != 0) && (CardManager.onPushArea3 != 0) && (((CardManager.onPushArea2 == 0) && ((CardManager.onPushArea1 == 0) || ((CardManager.selectCard.CompareTag("Spell") == 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0)))) || ((CardManager.selectCard.CompareTag("MagicType") == 0) && (CardManager.onPushArea2 != 0) && ((CardManager.onPushArea1 == 0) || ((CardManager.selectCard.CompareTag("Spell") == 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0)))) || ((CombineZone.magicTypeCards.Count != 0) && (CardManager.selectCard.CompareTag("MagicType") != 0) && (CardManager.onPushArea2 != 0) && ((CardManager.onPushArea1 == 0) || ((CardManager.selectCard.CompareTag("Spell") == 0) && (CardManager.onPushArea1 != 0)) || ((CombineZone.spellCards.Count != 0) && (CardManager.selectCard.CompareTag("Spell") != 0) && (CardManager.onPushArea1 != 0)))))))
            {
                Prs.pos = pos;                                             // IL_0008
                Prs.rot = rot;                                             // IL_000F
                Prs.scale = scale;                                         // IL_0016
            }

            // path: CardManager.CardAlignment -> CardManager.RoundAlignment -> Prs..ctor
            // unresolved condition (subject lost): i < objCount
            Prs.pos = pos;                                             // IL_0008
            Prs.rot = rot;                                             // IL_000F
            Prs.scale = scale;                                         // IL_0016

            // path: CardManager.Update -> CardManager.DragCard -> Prs..ctor
            if ((CardManager.isMyCardDrag != 0) && (InteractionLock.IsLocked == 0))
            {
                Prs.pos = pos;                                             // IL_0008
                Prs.rot = rot;                                             // IL_000F
                Prs.scale = scale;                                         // IL_0016
            }
        }

        [UnityLifecycle]
        [InspectorCallable]
        // reached from: CardManager.CardMouseDown, Card.OnMouseDown
        // called by: Cards.Card.CheckHighestCard
        // confidence partial/verified; gaps: unread-condition
        void CardMouseDown()
        {
            // path: CardManager.CardMouseDown
            CardManager.isMyCardDrag = 1;                              // IL_0002

            // path: Card.OnMouseDown -> Card.CheckHighestCard -> CardManager.CardMouseDown
            // unresolved condition (subject lost): RaycastHit2D.transform.gameObject.GetComponent() != null
            if ((InteractionLock.IsLocked == 0) && (/* unknown */))
            {
                CardManager.isMyCardDrag = 1;                              // IL_0002
            }
        }

        [InspectorCallable]
        // reached from: CardManager.CardMouseExit, Card.OnMouseExit
        // called by: Cards.Card.OnMouseExit
        // confidence verified
        void CardMouseExit(Card p0)
        {
            if ((CardManager.onPushArea3 == 0) && (CardManager.onPushArea2 == 0) && (CardManager.onPushArea1 == 0))
            {
                EnlargeCard(false, _);                                     // IL_001B
            }
        }

        [UnityLifecycle]
        [InspectorCallable]
        // reached from: CardManager.CardMouseOver, Card.OnMouseOver
        // called by: Cards.Card.OnMouseOver
        // confidence partial/verified; gaps: composed-on-same-object
        void CardMouseOver(Card p0)
        {
            // path: CardManager.CardMouseOver
            if (CardManager.onCardArea != 0)
            {
                EnlargeCard(true, _);                                      // IL_000B
            }

            // path: Card.OnMouseOver -> CardManager.CardMouseOver
            if ((InteractionLock.IsLocked == 0) && (CardManager.Inst.onCardArea != 0))
            {
                EnlargeCard(true, _);                                      // IL_000B
            }
        }

        [InspectorCallable]
        // reached from: CardManager.CardAlignment, CardManager.AddCard, CombineZone.ClearDropZone
        // called by: Cards.CardManager.AddCard, Combat.UI.CombineZone.ClearDropZone
        // confidence verified
        void CardAlignment()
        {
            RoundAlignment(CardManager.cardLeft, CardManager.cardRight, CardManager.myCards.Count, 0.5, Vector3.op_Multiply(_, 0.2)); // IL_0041

            // unresolved condition (subject lost): i < CardManager.myCards.Count
            Card.originPrs = originCardPrSs.Item[_];                   // IL_0061
            Card.MoveTransform(Card.originPrs, true, 0.7);             // IL_0072
            goto IL_0052;                                              // loop
        }

        [InspectorCallable]
        // reached from: CardManager.CardAlignment, CardManager.AddCard, CombineZone.ClearDropZone
        // called by: Cards.CardManager.AddCard, Combat.UI.CombineZone.ClearDropZone
        // confidence derived
        List<Prs> RoundAlignment(Transform p0, Transform p1, int p2, float p3, Vector3 p4)
        {
            // unresolved condition (subject lost): i < objCount
            new Prs(_, _, scale);                                      // IL_0068
            goto IL_0033;                                              // loop
        }

        [InspectorCallable]
        // called by: Battle.Turns.PlayerTurn.OnStart
        // confidence verified
        void AddCard()
        {
            Instantiate(CardManager.cardPrefab);                       // IL_0016
            PopWord();                                                 // IL_0023
            Card.Setup(CardManager.PopWord());                         // IL_0028
            SetOriginOrder();                                          // IL_003A
            CardAlignment();                                           // IL_0040
        }

        [InspectorCallable]
        // reached from: CardManager.AddCard
        // called by: Battle.Turns.PlayerTurn.OnStart
        // confidence partial; gaps: unread-condition
        void SetOriginOrder()
        {
            // unresolved condition (subject lost): i < CardManager.myCards.Count
            if ((/* unknown */))
            {
                Component.GetComponent().SetOriginOrder();                 // IL_0028
            }
        }

        [InspectorCallable]
        // reached from: CardManager.AddCard
        // called by: Battle.Turns.PlayerTurn.OnStart
        // confidence derived
        Word PopWord()
        {
            if (CardManager.wordBuffer.Count == 0)
            {
                SetupWordBuffer();                                         // IL_000E
            }
        }

        [UnityLifecycle]
        [InspectorCallable]
        // reached from: CardManager.AddCard, CardManager.Start
        // called by: Battle.Turns.PlayerTurn.OnStart
        // confidence derived
        void SetupWordBuffer()
        {
            // path: CardManager.AddCard -> CardManager.PopWord -> CardManager.SetupWordBuffer
            if (CardManager.wordBuffer.Count == 0)
            {
                CardManager.wordBuffer = /* ? */;                          // IL_0006
            }

            // path: CardManager.Start -> CardManager.SetupWordBuffer
            CardManager.wordBuffer = /* ? */;                          // IL_0006
        }

        [UnityLifecycle]
        // confidence verified
        void Update()
        {
            InteractionLock.IsLocked;                                  // IL_0000

            if (InteractionLock.IsLocked != 0)
            {
                CancelDrag();                                              // IL_0008
            }

            if (InteractionLock.IsLocked == 0)
            {
                DetectCardArea();                                          // IL_000F
            }

            if ((CardManager.isMyCardDrag != 0) && (InteractionLock.IsLocked == 0))
            {
                DragCard();                                                // IL_001D
            }
        }

        [UnityLifecycle]
        // reached from: CardManager.Update
        // confidence derived
        void DragCard()
        {
            if ((CardManager.isMyCardDrag != 0) && (InteractionLock.IsLocked == 0))
            {
                Util.MousePos;                                             // IL_0006
                Util.Qi;                                                   // IL_000B
                new Prs(Util.MousePos, Util.Qi, Prs.scale);                // IL_0020
                CardManager.selectCard.MoveTransform(_, false, 0);         // IL_002B
            }
        }

        [UnityLifecycle]
        // reached from: CardManager.Update
        // confidence derived
        void DetectCardArea()
        {
            if (InteractionLock.IsLocked == 0)
            {
                Util.MousePos;                                             // IL_0006
                CardManager.onCardArea = Array.Exists();                   // IL_0073
                CardManager.onPushArea1 = Array.Exists();                  // IL_008B
                CardManager.onPushArea2 = Array.Exists();                  // IL_00A3
                CardManager.onPushArea3 = Array.Exists();                  // IL_00BB
            }
        }

        [UnityLifecycle]
        // reached from: CardManager.Update
        // confidence partial; gaps: composed-on-same-object
        void CancelDrag()
        {
            if ((InteractionLock.IsLocked != 0) && (CardManager.isMyCardDrag != 0))
            {
                CardManager.isMyCardDrag = 0;                              // IL_000B
            }

            if ((InteractionLock.IsLocked != 0) && (CardManager.selectCard != null) && (CardManager.isMyCardDrag != 0))
            {
                CardManager.selectCard.MoveTransform(Card.originPrs, false, 0); // IL_0035
            }
        }

        [UnityLifecycle]
        // confidence verified
        void Start()
        {
            WordOS_state();                                            // IL_0001
            SetupWordBuffer();                                         // IL_0007
        }

        [UnityLifecycle]
        // reached from: CardManager.Start
        // confidence derived
        void WordOS_state()
        {
            if (MapMove.StagePosition == 0)
            {
                Word.percent = 1;                                          // IL_002F
                Word.percent = 0;                                          // IL_0042
                Word.percent = 0;                                          // IL_0055
                Word.percent = 1;                                          // IL_0068
                Word.percent = 0;                                          // IL_007B
                Word.percent = 0;                                          // IL_008E
                Word.percent = 0;                                          // IL_00A1
                Word.percent = 0;                                          // IL_00B4
            }

            if (MapMove.StagePosition == 1)
            {
                Word.percent = 1;                                          // IL_00C8
                Word.percent = 0;                                          // IL_00DB
                Word.percent = 1;                                          // IL_00EE
                Word.percent = 2;                                          // IL_0101
                Word.percent = 0;                                          // IL_0114
                Word.percent = 0;                                          // IL_0127
                Word.percent = 0;                                          // IL_013A
                Word.percent = 0;                                          // IL_014D
            }

            if (MapMove.StagePosition == 2)
            {
                Word.percent = 1;                                          // IL_0161
                Word.percent = 0;                                          // IL_0174
                Word.percent = 1;                                          // IL_0187
                Word.percent = 1;                                          // IL_019A
                Word.percent = 0;                                          // IL_01AD
                Word.percent = 1;                                          // IL_01C0
                Word.percent = 0;                                          // IL_01D3
                Word.percent = 0;                                          // IL_01E6
            }

            if (MapMove.StagePosition == 3)
            {
                Word.percent = 2;                                          // IL_01FA
                Word.percent = 0;                                          // IL_020D
                Word.percent = 2;                                          // IL_0220
                Word.percent = 1;                                          // IL_0233
                Word.percent = 1;                                          // IL_0246
                Word.percent = 1;                                          // IL_0259
                Word.percent = 0;                                          // IL_026C
                Word.percent = 1;                                          // IL_027F
            }

            if (MapMove.StagePosition == 4)
            {
                Word.percent = 5;                                          // IL_0293
                Word.percent = 5;                                          // IL_02A6
                Word.percent = 5;                                          // IL_02B9
                Word.percent = 3;                                          // IL_02CC
                Word.percent = 3;                                          // IL_02DF
                Word.percent = 3;                                          // IL_02F2
                Word.percent = 3;                                          // IL_0305
                Word.percent = 3;                                          // IL_0318
            }
        }

        [UnityLifecycle]
        // confidence verified
        void Awake()
        {
            CardManager.Inst = /* ? */;                                // IL_0001
            CardManager.Inst = /* ? */;                                // IL_0001
        }

        [UnityLifecycle]
        // reached from: CardManager.Awake
        // confidence derived
        CardManager Inst { set; }
        {
            CardManager.Inst = value;                                  // IL_0001
        }
    }
}
