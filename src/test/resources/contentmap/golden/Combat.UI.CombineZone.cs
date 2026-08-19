// generated from wv-editor capture -- pseudo-C#, not compilable
// evidence-derived: bodies show only observed statements, in IL offset order
// capture=editor schema=6 unity=2022.3.62f3 platform=OSXEditor backend=mono sdk=0.1.0

using UnityEngine;
using UnityEngine.SceneManagement;
using System.Collections;

namespace Combat.UI
{
    class CombineZone : MonoBehaviour
    {
        [UnityLifecycle]
        [InspectorCallable]
        // reached from: CardManager.CardMouseUp, Card.OnMouseUp, DropZone.GetCard, CombineZone.AddCard, DraggableCard.OnEndDrag
        // called by: Cards.Card.OnMouseUp, Cards.CardManager.CardMouseUp
        // confidence derived/partial; gaps: callee-condition-not-composed, composed-on-same-object
        void AddCard(GameObject p0)
        {
            // path: CardManager.CardMouseUp -> DropZone.GetCard -> CombineZone.AddCard | DropZone.GetCard -> CombineZone.AddCard
            if ((CombineZone.magicTypeCards.Count == 1) && (CombineZone.spellCards.Count == 1))
            {
                CombineZone.activateButton.gameObject.SetActive(true);     // IL_0078
                CombineZone.activateButton.onClick.AddListener(CombineZone.OnButtonClick); // IL_0099
            }

            // path: DraggableCard.OnEndDrag -> CombineZone.AddCard
            if ((DraggableCard.combineZone.CompareTag(Component.tag) != 0) && (DraggableCard.combineZone != null) && (DraggableCard.combineZone.magicTypeCards.Count == 1) && (DraggableCard.combineZone.spellCards.Count == 1))
            {
                CombineZone.activateButton.gameObject.SetActive(true);     // IL_0078
                CombineZone.activateButton.onClick.AddListener(CombineZone.OnButtonClick); // IL_0099
            }
        }

        [UnityLifecycle]
        [InspectorCallable]
        // reached from: SelectableObject.OnMouseDown, CombineZone.SetTarget
        // called by: Combat.Enemies.SelectableObject.OnMouseDown
        // confidence derived/verified
        void SetTarget(SelectableObject p0)
        {
            // path: SelectableObject.OnMouseDown -> CombineZone.SetTarget
            if ((InteractionLock.IsLocked == 0) && (SelectableObject.selectable != 0))
            {
                CombineZone.target = selectableObject;                     // IL_0002
            }

            // path: CombineZone.SetTarget
            CombineZone.target = selectableObject;                     // IL_0002
        }

        [InspectorCallable]
        // called by: Combat.UI.CombineZone/<OnButtonClick>d__17.MoveNext
        // confidence verified
        void ClearDropZone()
        {
            // unresolved condition (subject lost): Enumerator.Current != null
            // unresolved condition (subject lost): Enumerator.MoveNext() != 0
            CardManager.Inst;                                          // IL_0026
            CardManager.Inst.PopCard();                                // IL_002C
            Destroy(Enumerator.Current);                               // IL_0032

            // unresolved condition (subject lost): Enumerator.Current != null
            // unresolved condition (subject lost): Enumerator.MoveNext() != 0
            CardManager.Inst;                                          // IL_0077
            CardManager.Inst.PopCard();                                // IL_007E
            Destroy(Enumerator.Current);                               // IL_0084

            CardManager.Inst;                                          // IL_00A2
            CardManager.Inst.CardAlignment();                          // IL_00A7
            CombineZone.activateButton.gameObject.SetActive(false);    // IL_00CE
        }

        [InspectorCallable]
        // reached from: CombineZone.ClearDropZone
        // called by: Combat.UI.CombineZone/<OnButtonClick>d__17.MoveNext
        // confidence derived
        Prs(Vector3 p0, Quaternion p1, Vector3 p2)
        {
            // unresolved condition (subject lost): i < objCount
            Prs.pos = pos;                                             // IL_0008
            Prs.rot = rot;                                             // IL_000F
            Prs.scale = scale;                                         // IL_0016
        }

        [UnityLifecycle]
        // confidence verified
        void Update()
        {
            if ((CombineZone.magicTypeCards.Count == 1) && (CombineZone.spellCards.Count == 1))
            {
                CombineZone.activateButton.gameObject.SetActive(true);     // IL_0028
                CombineZone.activateButton.onClick.AddListener(CombineZone.OnButtonClick); // IL_0049
            }

            if ((CombineZone.spellCards.Count != 1) || ((CombineZone.magicTypeCards.Count != 1) && (CombineZone.spellCards.Count == 1)))
            {
                CombineZone.activateButton.gameObject.SetActive(false);    // IL_0066
            }
        }

        [UnityLifecycle]
        // confidence verified
        void Start()
        {
            CombineZone.activateButton.gameObject.SetActive(false);    // IL_000C
            Component.gameObject.SetActive(false);                     // IL_0018
        }

        [UnityLifecycle]
        // confidence partial; gaps: singleton-plumbing
        void Awake()
        {
            CombineZone.Instance = this;                               // IL_0001
        }
    }
}
